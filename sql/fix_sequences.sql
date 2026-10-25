DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'excel_document',
        'excel_sheet',
        'excel_sheet_chunk',
        'excel_sheet_chunk_delta',
        'excel_cell_history',
        'excel_collab_room',
        'excel_collab_log',
        'excel_edit_stack',
        'excel_export_task',
        'finance_import_batch',
        'finance_ledger_record'
    ]
    LOOP
        EXECUTE format(
            'SELECT setval(pg_get_serial_sequence(%L, ''id''), GREATEST((SELECT COALESCE(MAX(id), 1) FROM %I), 1), true)',
            t, t
        );
    END LOOP;
END
$$;
