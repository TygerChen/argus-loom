#!/usr/bin/env python3
"""Migrate DataLoom(MySQL) business data into Argus(PostgreSQL).

Tables are migrated in dependency order and are expected to share the same
column layout as the DataLoom MySQL schema.  Existing Argus rows are backed
up to JSON files next to this script before the matching tables are refilled.

Usage:
  python migrate_mysql_to_pg.py
  python migrate_mysql_to_pg.py --mysql-pass YCT412825 --pg-pass yct412825
"""

import argparse
import json
import math
import os
import sys
from collections import OrderedDict
from datetime import date, datetime
from decimal import Decimal

import pymysql
import psycopg2
from psycopg2.extras import execute_batch


TABLES = [
    "excel_document",
    "excel_sheet",
    "excel_sheet_chunk",
    "excel_sheet_chunk_delta",
    "excel_cell_history",
    "excel_collab_room",
    "excel_collab_log",
    "excel_edit_stack",
    "excel_operation_log",
    "excel_export_task",
    "excel_audit_log",
    "excel_system_config",
    "finance_import_batch",
    "finance_ledger_record",
]


def mysql_columns(conn, table):
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT column_name AS column_name, data_type AS data_type
            FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = %s
            ORDER BY ordinal_position
            """,
            (table,),
        )
        return [
            (row.get("column_name") or row.get("COLUMN_NAME"), row.get("data_type") or row.get("DATA_TYPE"))
            for row in cur.fetchall()
        ]


def pg_columns(conn, table):
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = %s
            ORDER BY ordinal_position
            """,
            (table,),
        )
        return [row[0] for row in cur.fetchall()]


def json_default(value):
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace")
    if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
        return None
    return str(value) if value is not None else None


def table_has_rows(conn, table):
    with conn.cursor() as cur:
        cur.execute(f"SELECT COUNT(*) FROM `{table}`")
        return int(cur.fetchone()["COUNT(*)"]) > 0


def table_exists(conn, table):
    with conn.cursor() as cur:
        cur.execute(
            "SELECT COUNT(*) FROM information_schema.tables "
            "WHERE table_schema = DATABASE() AND table_name = %s",
            (table,),
        )
        return int(cur.fetchone()["COUNT(*)"]) > 0


def backup_rows(pg_conn, table, columns, backup_dir):
    if not columns:
        return 0
    with pg_conn.cursor() as cur:
        cur.execute(f'SELECT {", ".join(columns)} FROM public."{table}"')
        rows = cur.fetchall()
    if not rows:
        return 0
    os.makedirs(backup_dir, exist_ok=True)
    path = os.path.join(backup_dir, f"{table}.json")
    with open(path, "w", encoding="utf-8") as fp:
        json.dump(
            [dict(zip(columns, [json_default(v) for v in row])) for row in rows],
            fp,
            ensure_ascii=False,
            indent=1,
        )
    print(f"[backup] {table}: {len(rows)} rows -> {path}")
    return len(rows)


def migrate_table(mysql_conn, pg_conn, table, args):
    dry_run = args.dry_run
    src_cols = [name for name, _ in mysql_columns(conn=mysql_conn, table=table)]
    dst_cols = pg_columns(pg_conn, table)
    shared = [c for c in dst_cols if c in src_cols]
    missing = [c for c in src_cols if c not in dst_cols]
    if not shared:
        print(f"[skip] {table}: no shared columns")
        return

    with mysql_conn.cursor() as cur:
        cur.execute(f'SELECT {", ".join(shared)} FROM `{table}`')
        rows = cur.fetchall()
        rows = [tuple(row[col] for col in shared) for row in rows]

    if dry_run:
        print(f"[plan] {table}: {len(rows)} rows, columns={shared}, skipped_missing={missing}")
        return

    with pg_conn.cursor() as cur:
        cur.execute(f'TRUNCATE TABLE "{table}"')
        if rows:
            execute_batch(
                cur,
                f'INSERT INTO "{table}" ({", ".join(shared)}) VALUES ({", ".join(["%s"] * len(shared))})',
                rows,
                page_size=500,
            )
    print(f"[migrate] {table}: {len(rows)} rows")


def reset_sequences(pg_conn, tables):
    with pg_conn.cursor() as cur:
        for table in tables:
            try:
                cur.execute(
                    """
                    SELECT setval(
                        pg_get_serial_sequence(%s, 'id'),
                        GREATEST((SELECT COALESCE(MAX(id), 1) FROM %I), 1),
                        true
                    )
                    """,
                    (table,),
                )
                print(f"[sequence] {table} -> {cur.fetchone()[0]}")
            except Exception as exc:
                print(f"[sequence] {table} skipped: {exc}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mysql-host", default="127.0.0.1")
    parser.add_argument("--mysql-port", type=int, default=3306)
    parser.add_argument("--mysql-user", default="root")
    parser.add_argument("--mysql-password", default="YCT412825")
    parser.add_argument("--mysql-db", default="dataloom")
    parser.add_argument("--pg-host", default="localhost")
    parser.add_argument("--pg-port", type=int, default=5432)
    parser.add_argument("--pg-user", default="postgres")
    parser.add_argument("--pg-password", default="yct412825")
    parser.add_argument("--pg-db", default="argus_rag")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    conn = pymysql.connect(
        host=args.mysql_host,
        port=args.mysql_port,
        user=args.mysql_user,
        password=args.mysql_password,
        database=args.mysql_db,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )
    pg = psycopg2.connect(
        host=args.pg_host,
        port=args.pg_port,
        user=args.pg_user,
        password=args.pg_password,
        dbname=args.pg_db,
    )
    pg.autocommit = False

    here = os.path.dirname(os.path.abspath(__file__))
    backup_dir = os.path.join(here, "backups", "pre-mysql-migrate")
    try:
        for table in TABLES:
            if not table_exists(conn, table):
                print(f"[skip] {table}: missing in MySQL")
                continue
            if not args.dry_run and not table_has_rows(conn, table):
                continue
            dst = pg_columns(pg, table)
            if not dst:
                print(f"[skip] {table}: missing in PostgreSQL")
                continue
            if not args.dry_run:
                backup_rows(pg, table, dst, backup_dir)
            migrate_table(conn, pg, table, args)
        pg.commit()
        if not args.dry_run:
            reset_sequences(pg, TABLES)
    except Exception as exc:
        pg.rollback()
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
    finally:
        conn.close()
        pg.close()
    print("DONE" if not args.dry_run else "DRY-RUN COMPLETE")


if __name__ == "__main__":
    main()
