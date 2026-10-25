"""End-to-end smoke test for the Argus backend API surface.

Usage:  python scripts/full_smoke_test.py

It creates temporary users/groups/documents, exercises the main product
modules, and cleans up the temporary Excel documents via the recycle bin.
"""

from __future__ import annotations

import hashlib
import io
import sys
import tempfile
import time
from pathlib import Path

import requests
from openpyxl import Workbook


BASE = "http://localhost:10001"
ADMIN_LOGIN = {"loginId": "admin", "password": "admin123"}
PASSWORD = "SmokePass1!"
NEW_PASSWORD = "SmokePwd123!"

SUFFIX = str(int(time.time() * 1000))
PREFIX = f"smoke_{SUFFIX}"

results: list[tuple[str, str, bool, str]] = []


def call(
    method: str,
    path: str,
    token: str | None = None,
    json_body: object | None = None,
    data: dict | None = None,
    files: dict | None = None,
    params: dict | None = None,
    timeout: int = 90,
    stream: bool = False,
):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return requests.request(
        method,
        BASE + path,
        headers=headers,
        json=json_body,
        data=data,
        files=files,
        params=params,
        timeout=timeout,
        stream=stream,
    )


def mark(category: str, name: str, ok: bool, detail: str = "") -> None:
    results.append((category, name, ok, detail))
    status = "PASS" if ok else "FAIL"
    suffix = f" | {detail}" if detail else ""
    print(f"[{status}] {category} :: {name}{suffix}")


def payload_of(resp: requests.Response):
    try:
        return resp.json()
    except Exception:
        return None


def check_json(
    category: str,
    name: str,
    method: str,
    path: str,
    token: str | None = None,
    expected_status: int = 200,
    json_body: object | None = None,
    data: dict | None = None,
    files: dict | None = None,
    params: dict | None = None,
    timeout: int = 90,
    accept_error_body: bool = False,
):
    try:
        resp = call(
            method,
            path,
            token=token,
            json_body=json_body,
            data=data,
            files=files,
            params=params,
            timeout=timeout,
        )
    except Exception as exc:
        mark(category, name, False, f"request error: {exc}")
        return None

    status_ok = resp.status_code == expected_status
    body = payload_of(resp)
    body_ok = True
    detail = ""
    if expected_status >= 400:
        ok = status_ok
        detail = "" if ok else f"http={resp.status_code} expected={expected_status}"
    else:
        if resp.status_code != expected_status:
            detail = f"http={resp.status_code} expected={expected_status}"
        elif body is not None and isinstance(body, dict) and body.get("success") is False:
            detail = f"body={body.get('message')}"
            body_ok = accept_error_body
        ok = status_ok and body_ok
    mark(category, name, ok, detail)
    return body


def login(login_id: str, password: str):
    resp = call("POST", "/api/auth/login", json_body={"loginId": login_id, "password": password})
    if resp.status_code != 200:
        raise RuntimeError(f"login failed: {resp.status_code} {resp.text[:500]}")
    body = resp.json()
    if not body.get("success"):
        raise RuntimeError(f"login body failed: {body}")
    return body["data"]["accessToken"], body["data"]["currentUser"]


def register(username: str, email: str, display: str, password: str) -> None:
    resp = call(
        "POST",
        "/api/auth/register",
        json_body={
            "username": username,
            "email": email,
            "displayName": display,
            "password": password,
        },
    )
    if resp.status_code != 200:
        raise RuntimeError(f"register failed {resp.status_code}: {resp.text[:500]}")
    body = payload_of(resp)
    if body is not None and isinstance(body, dict) and body.get("success") is False:
        raise RuntimeError(f"register body failed: {body}")


def find_doc(body, doc_id):
    if not isinstance(body, dict):
        return None
    data = body.get("data")
    if not isinstance(data, list):
        return None
    for item in data:
        if str(item.get("documentId") or item.get("id") or "") == str(doc_id):
            return item
    return None


def wait_doc_ready(token: str, group_id: int, doc_id, timeout: int = 150) -> str | None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            resp = call("GET", "/api/documents", token=token, params={"groupId": group_id})
            body = payload_of(resp)
            item = find_doc(body, doc_id)
            if not item:
                time.sleep(2)
                continue
            status = str(item.get("status") or item.get("processStatus") or "")
            upper = status.upper()
            if upper in ("READY", "SUCCESS", "COMPLETED"):
                return status
            if upper in ("FAILED", "ERROR"):
                return status
        except Exception:
            pass
        time.sleep(2)
    return None


def png_bytes() -> bytes:
    # Minimal valid 1x1 PNG.
    return bytes([
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
        0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
        0x54, 0x78, 0x9C, 0x63, 0xF8, 0xFF, 0x00, 0x3F,
        0x00, 0x05, 0xFE, 0x02, 0x12, 0xA7, 0x5C, 0x84,
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
    ])


def make_md_file() -> Path:
    path = Path(tempfile.gettempdir()) / f"smoke_kb_{SUFFIX}.md"
    path.write_text(
        "# Argus Smoke Knowledge\n"
        f"UniqueCode {SUFFIX} describes the Argus RAG platform.\n"
        "The flagship model released in 2026 and supports citations.\n",
        encoding="utf-8",
    )
    return path


def make_txt_file() -> Path:
    path = Path(tempfile.gettempdir()) / f"smoke_chunked_{SUFFIX}.txt"
    path.write_text(
        ("Chunked upload payload for smoke tests. " * 800) + f"END-{SUFFIX}\n",
        encoding="utf-8",
    )
    return path


def make_xlsx_file() -> Path:
    path = Path(tempfile.gettempdir()) / f"smoke_excel_{SUFFIX}.xlsx"
    wb = Workbook()
    ws = wb.active
    ws.title = "Sheet1"
    ws.append(["Item", "Qty", "Year"])
    ws.append(["Widget A", 10, 2024])
    ws.append(["Widget B", 25, 2025])
    ws.append(["Widget C", 40, 2026])
    wb.save(path)
    return path


def make_finance_xlsx_file() -> Path:
    path = Path(tempfile.gettempdir()) / f"smoke_finance_{SUFFIX}.xlsx"
    wb = Workbook()
    ws = wb.active
    ws.title = "\u8d26\u672c"
    ws.append(["\u65e5\u671f", "\u6458\u8981", "\u503f\u65b9", "\u8d37\u65b9", "\u4f59\u989d"])
    ws.append(["2026-01-02", "\u91c7\u8d2d\u6d4b\u8bd5", 120.00, 0, 120.00])
    ws.append(["2026-01-03", "\u9500\u552e\u56de\u6b3e", 0, 80.00, 40.00])
    ws.append(["2026-01-04", "\u5176\u4ed6\u6536\u5165", 60.00, 0, 100.00])
    wb.save(path)
    return path


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> int:
    print("== Argus backend full smoke test ==")
    print(f"suffix: {SUFFIX}")

    # --------------------------------------------------------------- auth
    print("\n-- auth / account --")
    check_json("auth", "unauthenticated /me returns 401", "GET", "/api/auth/me",
               expected_status=401)
    try:
        admin_token, admin_user = login(ADMIN_LOGIN["loginId"], ADMIN_LOGIN["password"])
        mark("auth", "admin login", True, f"userId={admin_user.get('userId')}")
    except Exception as exc:
        mark("auth", "admin login", False, str(exc))
        return finish()
    check_json("auth", "admin /me", "GET", "/api/auth/me", token=admin_token)

    owner = f"{PREFIX}_owner"
    owner_email = f"owner.{SUFFIX}@test.local"
    member1 = f"{PREFIX}_m1"
    member1_email = f"m1.{SUFFIX}@test.local"
    member2 = f"{PREFIX}_m2"
    member2_email = f"m2.{SUFFIX}@test.local"

    try:
        register(owner, owner_email, f"Smoke Owner {SUFFIX}", PASSWORD)
        register(member1, member1_email, f"Smoke Member1 {SUFFIX}", PASSWORD)
        register(member2, member2_email, f"Smoke Member2 {SUFFIX}", PASSWORD)
        mark("auth", "register 3 users", True)
    except Exception as exc:
        mark("auth", "register users", False, str(exc))
        return finish()

    try:
        owner_token, owner_profile = login(owner, PASSWORD)
        member1_token, member1_profile = login(member1, PASSWORD)
        member2_token, member2_profile = login(member2, PASSWORD)
        mark("auth", "login business users", True)
    except Exception as exc:
        mark("auth", "login business users", False, str(exc))
        return finish()

    owner_id = owner_profile["userId"]
    member1_id = member1_profile["userId"]
    member2_id = member2_profile["userId"]

    refresh_session = requests.Session()
    login_resp = refresh_session.post(
        BASE + "/api/auth/login",
        json={"loginId": owner, "password": PASSWORD},
        timeout=30,
    )
    refresh_ok = False
    if login_resp.status_code == 200:
        refresh_resp = refresh_session.post(BASE + "/api/auth/refresh", timeout=30)
        if refresh_resp.status_code == 200:
            refresh_payload = refresh_resp.json()
            refresh_token = refresh_payload.get("data", {}).get("accessToken")
            me_resp = call("GET", "/api/auth/me", token=refresh_token)
            refresh_ok = me_resp.status_code == 200
        logout_resp = refresh_session.post(BASE + "/api/auth/logout", timeout=30)
        mark("auth", "logout", logout_resp.status_code in (200, 204))
    else:
        mark("auth", "logout", False, "login step failed")
    mark("auth", "refresh token", refresh_ok)

    check_json("account", "change password", "POST", "/api/account/change-password",
               token=owner_token,
               json_body={"currentPassword": PASSWORD, "newPassword": NEW_PASSWORD})
    try:
        owner_token, _ = login(owner, NEW_PASSWORD)
        mark("account", "login after password change", True)
    except Exception as exc:
        mark("account", "login after password change", False, str(exc))

    # --------------------------------------------------------- admin users
    print("\n-- admin users --")
    user_row = None
    users_body = payload_of(call("GET", "/api/admin/users", token=admin_token))
    if isinstance(users_body, dict) and isinstance(users_body.get("data"), list):
        user_row = next(
            (u for u in users_body["data"]
             if str(u.get("userId") or u.get("id")) == str(owner_id)), None
        )
    mark("admin users", "list users", user_row is not None)
    check_json("admin users", "user detail", "GET", f"/api/admin/users/{owner_id}",
               token=admin_token)
    check_json("admin users", "disable smoke user", "PATCH",
               f"/api/admin/users/{owner_id}/status",
               token=admin_token, json_body={"status": "DISABLED"})
    disabled_resp = call("GET", "/api/auth/me", token=owner_token)
    mark("admin users", "disabled token rejected",
         disabled_resp.status_code in (401, 403),
         f"http={disabled_resp.status_code}")
    check_json("admin users", "re-enable smoke user", "PATCH",
               f"/api/admin/users/{owner_id}/status",
               token=admin_token, json_body={"status": "ACTIVE"})
    try:
        owner_token, _ = login(owner, NEW_PASSWORD)
        mark("admin users", "re-login after enable", True)
    except Exception as exc:
        mark("admin users", "re-login after enable", False, str(exc))

    # -------------------------------------------------------------- groups
    print("\n-- groups / collaboration --")
    group_body = payload_of(call("POST", "/api/groups", token=owner_token,
                                 json_body={"name": f"Smoke Group {SUFFIX}",
                                            "description": "ref test"}))
    group_id = (group_body or {}).get("data")
    mark("groups", "create group", isinstance(group_id, int), f"group={group_id}")

    group_code = None
    my_groups = payload_of(call("GET", "/api/groups/my", token=owner_token))
    if isinstance(my_groups, dict) and isinstance(my_groups.get("data"), dict):
        owned = my_groups["data"].get("ownedGroups") or []
        group_code = next(
            (g.get("groupCode") for g in owned if g.get("groupId") == group_id), None
        )
    mark("groups", "owner groups list", group_code is not None, f"code={group_code}")

    req_payload = payload_of(call("POST", "/api/groups/join-requests",
                                  token=member1_token,
                                  json_body={"groupCode": group_code}))
    join_req_id = (req_payload or {}).get("data")
    mark("groups", "member submit join request", isinstance(join_req_id, int))
    check_json("groups", "my join requests", "GET", "/api/groups/join-requests/my",
               token=member1_token)
    check_json("groups", "owner pending join requests", "GET",
               f"/api/groups/{group_id}/join-requests", token=owner_token)
    check_json("groups", "approve join request", "POST",
               f"/api/groups/{group_id}/join-requests/{join_req_id}/approve",
               token=owner_token)

    inv_payload = payload_of(call("POST", f"/api/groups/{group_id}/invitations",
                                  token=owner_token,
                                  json_body={"inviteeUserId": member2_id}))
    invitation_id = (inv_payload or {}).get("data")
    mark("groups", "create invitation", isinstance(invitation_id, int))
    check_json("groups", "my sent invitations", "GET", "/api/groups/invitations/my-sent",
               token=owner_token)
    check_json("groups", "accept invitation", "POST",
               f"/api/invitations/{invitation_id}/accept", token=member2_token)

    members_body = payload_of(call("GET", f"/api/groups/{group_id}/members",
                                   token=owner_token))
    member_ids = {str(m.get("userId")) for m in ((members_body or {}).get("data") or []) if m}
    mark("groups", "members list after joins",
         str(member1_id) in member_ids and str(member2_id) in member_ids,
         f"members={member_ids}")

    check_json("groups", "member leaves group", "POST", f"/api/groups/{group_id}/leave",
               token=member2_token)
    check_json("groups", "owner removes member", "DELETE",
               f"/api/groups/{group_id}/members/{member1_id}", token=owner_token)

    inv_reject_payload = payload_of(call("POST", f"/api/groups/{group_id}/invitations",
                                         token=owner_token,
                                         json_body={"inviteeUserId": member2_id}))
    inv_reject_id = (inv_reject_payload or {}).get("data")
    check_json("groups", "reject invitation", "POST",
               f"/api/invitations/{inv_reject_id}/reject", token=member2_token)
    inv_cancel_payload = payload_of(call("POST", f"/api/groups/{group_id}/invitations",
                                         token=owner_token,
                                         json_body={"inviteeUserId": member1_id}))
    inv_cancel_id = (inv_cancel_payload or {}).get("data")
    check_json("groups", "cancel invitation", "POST",
               f"/api/invitations/{inv_cancel_id}/cancel", token=owner_token)

    # ------------------------------------------------------------- documents
    print("\n-- document / knowledge base --")
    md_file = make_md_file()
    with md_file.open("rb") as fh:
        upload_resp = call("POST", "/api/documents/upload", token=owner_token,
                           files={"file": (md_file.name, fh, "text/markdown")},
                           data={"groupId": str(group_id)}, timeout=120)
    upload_body = payload_of(upload_resp)
    doc_id = (upload_body or {}).get("data") if isinstance(upload_body, dict) else None
    mark("documents", "direct upload", upload_resp.status_code == 200 and doc_id is not None,
         f"doc={doc_id}")

    list_body = payload_of(call("GET", "/api/documents", token=owner_token,
                                params={"groupId": group_id}))
    mark("documents", "list documents", isinstance(list_body, dict) and isinstance(
        list_body.get("data"), list))

    if doc_id:
        status = wait_doc_ready(owner_token, group_id, doc_id)
        mark("documents", "ingestion reaches READY", status is not None
             and status.upper() not in ("FAILED", "ERROR"), f"status={status}")
        preview = payload_of(call("GET", f"/api/documents/{doc_id}/preview",
                                  token=owner_token, params={"groupId": group_id}))
        mark("documents", "preview", isinstance(preview, dict)
             and isinstance(preview.get("data"), dict))
        download = call("GET", f"/api/documents/{doc_id}/download",
                        token=owner_token, params={"groupId": group_id}, timeout=30)
        mark("documents", "download", download.status_code == 200
             and len(download.content or b"") > 0,
             f"bytes={len(download.content)}")

    # chunked upload path
    txt_file = make_txt_file()
    content = txt_file.read_bytes()
    chunk_count = 2
    chunk_size = (len(content) + 1) // chunk_count
    init_body = payload_of(call("POST", "/api/documents/upload/init", token=owner_token,
                                json_body={
                                    "groupId": group_id,
                                    "fileName": txt_file.name,
                                    "fileSize": len(content),
                                    "contentType": "text/plain",
                                    "fileHash": sha256_bytes(content),
                                    "chunkSize": chunk_size,
                                    "chunkCount": chunk_count,
                                }))
    session = (init_body or {}).get("data", {}) if isinstance(init_body, dict) else {}
    upload_id = session.get("uploadId") or session.get("sessionId")
    mark("documents", "chunk upload init", upload_id is not None,
         f"instant={session.get('instantUpload')}")

    chunk_doc_id = None
    if upload_id:
        all_ok = True
        for idx in range(chunk_count):
            part = content[idx * chunk_size:(idx + 1) * chunk_size]
            resp = call("POST", "/api/documents/upload/chunks", token=owner_token,
                        files={"chunk": (f"part{idx}", io.BytesIO(part), "application/octet-stream")},
                        data={"uploadId": upload_id, "chunkIndex": str(idx),
                              "chunkHash": sha256_bytes(part)}, timeout=60)
            if resp.status_code != 200:
                all_ok = False
                break
        mark("documents", "chunk upload pieces", all_ok)
        if all_ok:
            status_resp = call("GET", f"/api/documents/upload/{upload_id}",
                               token=owner_token)
            status_body = payload_of(status_resp)
            progress = status_body.get("data", {}) if status_body else {}
            mark("documents", "chunk upload status", status_resp.status_code == 200
                 and progress.get("chunkCount") == chunk_count)
            complete_resp = call("POST", f"/api/documents/upload/{upload_id}/complete",
                                 token=owner_token, timeout=30)
            complete_body = payload_of(complete_resp)
            chunk_doc_id = complete_body.get("data") if complete_body else None
            mark("documents", "chunk upload complete", complete_resp.status_code == 200
                 and chunk_doc_id is not None)
            if chunk_doc_id:
                status2 = wait_doc_ready(owner_token, group_id, chunk_doc_id)
                mark("documents", "chunked doc READY", status2 is not None
                     and status2.upper() not in ("FAILED", "ERROR"), f"status={status2}")

    # ------------------------------------------------------------ RAG chat
    print("\n-- QA / RAG chat --")
    question = f"What year is the flagship model release per UniqueCode {SUFFIX}?"
    ask_resp = call("POST", "/api/qa/ask", token=owner_token,
                    json_body={"groupId": group_id, "question": question}, timeout=180)
    ask_body = payload_of(ask_resp)
    ask_data = ask_body if isinstance(ask_body, dict) else {}
    record_id = ask_data.get("recordId")
    answered = ask_data.get("answered") is True
    mark("qa", "sync ask", ask_resp.status_code == 200
         and isinstance(ask_body, dict) and ask_body.get("success") is not False,
         f"answered={ask_data.get('answered')} recordId={record_id}")

    stream_resp = call("POST", "/api/qa/stream-ask", token=owner_token,
                       json_body={"groupId": group_id, "question": question},
                       timeout=180, stream=True)
    events = {}
    current = None
    token_count = 0
    try:
        for raw in stream_resp.iter_lines(decode_unicode=True):
            if not raw:
                continue
            if raw.startswith("event:"):
                current = raw[6:].strip()
            elif raw.startswith("data:"):
                value = raw[5:].strip()
                events.setdefault(current, []).append(value)
                if current == "token":
                    token_count += 1
    except Exception as exc:
        mark("qa", "stream-ask SSE parse", False, str(exc))
    else:
        mark("qa", "stream-ask SSE", stream_resp.status_code == 200
             and token_count > 0 and "error" not in events,
             f"tokens={token_count} events={sorted(events.keys())}")

    if record_id:
        check_json("qa", "record detail", "GET", f"/api/qa/records/{record_id}",
                   token=owner_token)
        check_json("qa", "rate record", "PUT", f"/api/qa/records/{record_id}/rate",
                   token=owner_token, params={"rating": "THUMBS_UP"})
    check_json("qa", "records list", "GET", "/api/qa/records",
               token=owner_token, params={"groupId": group_id})

    # ------------------------------------------------------- assistant chat
    print("\n-- assistant sessions / chat --")
    session_payload = payload_of(call("POST", "/api/assistant/sessions",
                                      token=owner_token, json_body={}))
    session_data = (session_payload or {}).get("data") if isinstance(session_payload, dict) else {}
    session_id = (session_data or {}).get("sessionId") or (session_data or {}).get("id")
    mark("assistant", "create session", session_id is not None, f"session={session_id}")
    if session_id:
        check_json("assistant", "list sessions", "GET", "/api/assistant/sessions",
                   token=owner_token)
        check_json("assistant", "session detail", "GET",
                   f"/api/assistant/sessions/{session_id}", token=owner_token)
        check_json("assistant", "rename session", "PATCH",
                   f"/api/assistant/sessions/{session_id}", token=owner_token,
                   json_body={"title": f"Smoke Session {SUFFIX}"})
        chat_resp = call("POST", "/api/assistant/chat", token=owner_token,
                         json_body={"sessionId": session_id, "message": "Say hello.",
                                    "toolMode": "CHAT"}, timeout=180)
        chat_body = payload_of(chat_resp)
        mark("assistant", "sync chat", chat_resp.status_code == 200
             and isinstance(chat_body, dict) and chat_body.get("success") is not False,
             f"responseText={str((chat_body or {}).get('data'))[:120]}")

        kb_chat_resp = call("POST", "/api/assistant/chat", token=owner_token,
                            json_body={"sessionId": session_id,
                                       "message": question,
                                       "toolMode": "KB_SEARCH",
                                       "groupId": group_id}, timeout=180)
        kb_body = payload_of(kb_chat_resp)
        mark("assistant", "KB_SEARCH chat", kb_chat_resp.status_code == 200
             and isinstance(kb_body, dict) and kb_body.get("success") is not False)

        check_json("assistant", "session context", "GET",
                   f"/api/assistant/sessions/{session_id}/context", token=owner_token)
        stream_chat = call("POST", "/api/assistant/chat/stream", token=owner_token,
                           json_body={"sessionId": session_id, "message": "Stream test",
                                      "toolMode": "CHAT"}, timeout=180, stream=True)
        evts, tok = parse_sse(stream_chat)
        delta_events = len(evts.get("delta") or [])
        mark("assistant", "chat stream SSE", stream_chat.status_code == 200
             and (tok + delta_events) > 0 and "error" not in evts,
             f"tokens={tok} delta={delta_events} events={sorted(evts)}")
        check_json("assistant", "archive session", "POST",
                   f"/api/assistant/sessions/{session_id}/archive", token=owner_token)
        check_json("assistant", "restore session", "POST",
                   f"/api/assistant/sessions/{session_id}/restore", token=owner_token)
        check_json("assistant", "delete session", "DELETE",
                   f"/api/assistant/sessions/{session_id}", token=owner_token)

    check_json("models", "model list", "GET", "/api/models", token=owner_token)

    # ------------------------------------------------------- excel module
    print("\n-- excel --")
    xlsx_file = make_xlsx_file()
    excel_ids: list[int] = []
    with xlsx_file.open("rb") as fh:
        upload_resp = call("POST", "/api/excel/upload", token=owner_token,
                           files={"file": (xlsx_file.name, fh,
                                           "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")},
                           timeout=120)
    upload_body = payload_of(upload_resp)
    excel_payload = upload_body.get("data", {}) if isinstance(upload_body, dict) else {}
    excel_doc_id = excel_payload.get("documentId") or excel_payload.get("id")
    excel_ids.append(excel_doc_id)
    mark("excel", "upload xlsx", upload_resp.status_code == 200
         and excel_doc_id is not None, f"doc={excel_doc_id}")

    list_resp = payload_of(call("GET", "/api/excel/document/list", token=owner_token,
                                params={"pageNum": 1, "pageSize": 20}))
    excel_records = (list_resp or {}).get("data", {}).get("records") or []
    mark("excel", "document list", any(
        str(r.get("documentId") or r.get("id")) == str(excel_doc_id)
        for r in excel_records if r), f"total={len(excel_records)}")

    detail_body = payload_of(call("GET", f"/api/excel/document/{excel_doc_id}",
                                  token=owner_token))
    detail_data = (detail_body or {}).get("data") or {}
    sheets = detail_data.get("sheets") or []
    sheet_id = (sheets[0].get("sheetId") or sheets[0].get("id")) if sheets else None
    mark("excel", "document detail", bool(sheets and sheet_id),
         f"sheets={len(sheets)}")

    if sheet_id:
        cell_body = payload_of(call("GET",
                                    f"/api/excel/document/{excel_doc_id}/sheet/{sheet_id}/all",
                                    token=owner_token))
        cells = (cell_body or {}).get("data", {}).get("celldata") or []
        mark("excel", "load all celldata", isinstance(cells, list) and len(cells) > 0,
             f"cells={len(cells)}")

        schema_body = check_json("excel", "sheet schema", "GET",
                                 f"/api/excel/schema/{sheet_id}", token=owner_token)
        summary_body = check_json("excel", "structured summary", "GET",
                                  f"/api/excel/schema/document/{excel_doc_id}/summary",
                                  token=owner_token)
        query_body = check_json("excel", "structured query", "POST",
                                f"/api/excel/schema/{sheet_id}/query",
                                token=owner_token,
                                json_body={"column": "Item", "operator": "eq",
                                           "value": "Widget A"})

        check_json("excel", "create version", "POST",
                   f"/api/excel/document/{excel_doc_id}/versions",
                   token=owner_token, json_body={"summary": "smoke baseline"})

        update_payload = {"sheetId": sheet_id, "r": 1, "c": 1,
                          "v": {"v": "999", "m": "999",
                                "ct": {"t": "n", "fa": "General"}}}
        check_json("excel", "batch update cells", "PUT",
                   f"/api/excel/document/{excel_doc_id}/cells/batch",
                   token=owner_token, json_body=[update_payload])
        check_json("excel", "audit logs", "GET",
                   f"/api/excel/{excel_doc_id}/audit/logs", token=owner_token)
        check_json("excel", "undo edit", "POST",
                   f"/api/excel/{excel_doc_id}/audit/undo", token=owner_token)
        check_json("excel", "redo edit", "POST",
                   f"/api/excel/{excel_doc_id}/audit/redo", token=owner_token)
        check_json("excel", "repair number format", "POST",
                   f"/api/excel/document/{excel_doc_id}/repair-number-format",
                   token=owner_token)

        versions_body = payload_of(call("GET",
                                        f"/api/excel/document/{excel_doc_id}/versions",
                                        token=owner_token))
        versions = (versions_body or {}).get("data", {}).get("records") or []
        version_id = versions[0].get("id") if versions else None
        mark("excel", "version list", version_id is not None, f"versions={len(versions)}")
        if version_id:
            check_json("excel", "version detail", "GET",
                       f"/api/excel/document/{excel_doc_id}/versions/{version_id}",
                       token=owner_token)
            check_json("excel", "version preview", "GET",
                       f"/api/excel/document/{excel_doc_id}/versions/{version_id}/preview",
                       token=owner_token)
            check_json("excel", "version restore", "POST",
                       f"/api/excel/document/{excel_doc_id}/versions/{version_id}/restore",
                       token=owner_token)

        current_cells = cells
        workbook_sheet = {
            "name": "Sheet1",
            "row": (detail_data.get("sheets") or [{}])[0].get("totalRows") or 10,
            "column": 3,
            "config": {},
            "celldata": current_cells,
        }
        wb_resp = call("PUT", f"/api/excel/document/{excel_doc_id}/workbook",
                       token=owner_token, json_body={"sheets": [workbook_sheet]},
                       timeout=120)
        wb_body = payload_of(wb_resp)
        wb_data = (wb_body or {}).get("data") or {}
        mark("excel", "save workbook", wb_resp.status_code == 200
             and wb_body and wb_body.get("success") is not False,
             f"sheetIdMap={wb_data.get('sheetIdMap')}")

        check_json("excel", "rename document", "PUT",
                   f"/api/excel/document/{excel_doc_id}/name", token=owner_token,
                   json_body={"name": f"Smoke Excel {SUFFIX}"})

        asset_key = None
        asset_resp = call("POST", "/api/excel/assets", token=owner_token,
                          files={"file": ("smoke.png", png_bytes(), "image/png")},
                          data={"documentId": str(excel_doc_id)}, timeout=30)
        asset_body = payload_of(asset_resp)
        if asset_body and isinstance(asset_body, dict):
            asset_key = asset_body.get("data", {}).get("assetKey")
        mark("excel", "asset upload", asset_resp.status_code == 200
             and asset_key is not None, f"key={asset_key}")
        if asset_key:
            get_asset = call("GET", f"/api/excel/assets/{asset_key}", token=owner_token)
            mark("excel", "asset fetch", get_asset.status_code == 200
                 and len(get_asset.content) > 0)
        mig_body = payload_of(call("POST", "/api/excel/assets/migrate-legacy",
                                   token=owner_token, params={"limit": 50}))
        mark("excel", "legacy asset migration", isinstance(mig_body, dict)
             and mig_body.get("success") is not False
             and isinstance(mig_body.get("data"), dict))

        # 财务接口在 SecurityConfig 是 URL 级 ROLE_FINANCE 门禁（fail-closed）：
        # 普通 USER token 打 /api/finance/** 必然 403，这是正确行为。
        # 因此先由管理员把 owner 提升为 FINANCE 再重新登录（角色在 JWT 里），
        # 财务段全部改用新 token——2026-10-24 之前此段用 owner_token 必挂 5 项。
        role_resp = call("PATCH", f"/api/admin/users/{owner_id}/role",
                         token=admin_token, json_body={"systemRole": "FINANCE"})
        mark("finance", "grant finance role", role_resp.status_code == 200,
             f"http={role_resp.status_code}")
        # 注意：account 段已把 owner 密码改为 NEW_PASSWORD，此处必须用新密码重登
        finance_token, _ = login(owner, NEW_PASSWORD)

        finance_file = make_finance_xlsx_file()
        with finance_file.open("rb") as fh:
            fin_resp = call("POST", "/api/finance/import", token=finance_token,
                            files={"file": (finance_file.name, fh,
                                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")},
                            data={"bookKey": f"smoke-{SUFFIX}"}, timeout=120)
        fin_body = payload_of(fin_resp)
        fin_data = fin_body.get("data", {}) if isinstance(fin_body, dict) else {}
        batch_id = fin_data.get("batchId")
        finance_doc_id = fin_data.get("documentId")
        if finance_doc_id:
            excel_ids.append(finance_doc_id)
        mark("finance", "import workbook", fin_resp.status_code == 200
             and fin_body and fin_body.get("success") is not False,
             f"batch={batch_id} mapped={fin_data.get('mappedRows')}")
        if batch_id:
            check_json("finance", "import batches", "GET", "/api/finance/import-batches",
                       token=finance_token, params={"pageNum": 1, "pageSize": 20})
            check_json("finance", "batch records", "GET",
                       f"/api/finance/import-batches/{batch_id}/records",
                       token=finance_token)
            check_json("finance", "finance batch versions", "GET",
                       "/api/finance/batch-versions", token=finance_token,
                       params={"bookKey": f"smoke-{SUFFIX}"})
            if fin_data.get("mappedRows"):
                check_json("finance", "activate batch", "POST",
                           f"/api/finance/batches/{batch_id}/activate", token=finance_token)
                check_json("finance", "batch update records", "PUT",
                           "/api/finance/records/batch", token=finance_token,
                           json_body=[], accept_error_body=True)
        check_json("finance", "finance overview", "GET", "/api/finance/overview",
                   token=finance_token)
        check_json("finance", "account summary", "GET", "/api/finance/summary/accounts",
                   token=finance_token)
        check_json("finance", "daily summary", "GET", "/api/finance/summary/daily",
                   token=finance_token)
        check_json("finance", "finance records", "GET", "/api/finance/records",
                   token=finance_token)

        export_resp = call("POST", f"/api/excel/export/document/{excel_doc_id}",
                           token=owner_token)
        export_body = payload_of(export_resp)
        export_data = (export_body or {}).get("data") or {}
        export_id = export_data.get("id")
        mark("excel", "create export task", export_resp.status_code == 200
             and export_id is not None, f"task={export_id}")
        if export_id:
            export_ok = False
            for _ in range(45):
                task_body = payload_of(call("GET",
                                            f"/api/excel/export/tasks/{export_id}",
                                            token=owner_token))
                task = (task_body or {}).get("data") or {}
                raw_status = task.get("status")
                status = str(raw_status or "").upper()
                if raw_status == 2 or any(k in status for k in ("SUCCESS", "COMPLETED", "DONE")):
                    export_ok = True
                    break
                if raw_status == 3 or "FAIL" in status or "ERROR" in status:
                    break
                time.sleep(2)
            mark("excel", "export task completes", export_ok, f"status={status}")
            dl = call("GET", f"/api/excel/export/tasks/{export_id}/download",
                      token=owner_token, timeout=60)
            mark("excel", "export download", dl.status_code == 200
                 and len(dl.content) > 0, f"bytes={len(dl.content)}")
            check_json("excel", "export task list", "GET", "/api/excel/export/tasks",
                       token=owner_token)
            cancel_resp = call("DELETE", f"/api/excel/export/tasks/{export_id}",
                               token=owner_token)
            cancel_body = payload_of(cancel_resp)
            # 两种可接受结果：取消成功(200)；或任务已先完成、服务正确拒绝取消
            # (409/EXCEL_EXPORT_STATE——小工作簿导出常在 cancel 到达前完成)。
            cancel_msg = str((cancel_body or {}).get("message") or "")
            cancel_code = str((cancel_body or {}).get("code") or "")
            mark("excel", "cancel export task",
                 cancel_resp.status_code == 200
                 or (cancel_resp.status_code in (400, 409)
                     and ("取消" in cancel_msg or cancel_code == "EXCEL_EXPORT_STATE")),
                 f"http={cancel_resp.status_code} body={str(cancel_body)[:120]}")

        # share link + ACL
        share_body = payload_of(call("POST",
                                     f"/api/excel/document/{excel_doc_id}/share",
                                     token=owner_token, params={"role": "VIEWER"}))
        share_token = (share_body or {}).get("data", {}).get("token")
        mark("excel", "create share link", share_body is not None
             and isinstance(share_body, dict) and bool(share_token), f"token={share_token}")
        if share_token:
            check_json("excel", "resolve share link", "GET",
                       f"/api/excel/document/{excel_doc_id}/share/{share_token}",
                       token=owner_token)
            check_json("excel", "revoke share link", "DELETE",
                       f"/api/excel/document/{excel_doc_id}/share/{share_token}",
                       token=owner_token)

        check_json("excel", "list ACL", "GET",
                   f"/api/excel/document/{excel_doc_id}/acl", token=owner_token)
        check_json("excel", "ACL share", "PUT",
                   f"/api/excel/document/{excel_doc_id}/acl/share", token=owner_token,
                   json_body={"userId": member1_id, "role": "EDITOR"})
        member_view = call("GET", f"/api/excel/document/{excel_doc_id}",
                           token=member1_token)
        mark("excel", "shared member can view", member_view.status_code == 200)
        check_json("excel", "ACL revoke", "DELETE",
                   f"/api/excel/document/{excel_doc_id}/acl/{member1_id}",
                   token=owner_token)

        # ---- transfer owner round-trip
        second_file = Path(tempfile.gettempdir()) / f"smoke_excel2_{SUFFIX}.xlsx"
        wb2 = Workbook()
        ws2 = wb2.active
        ws2.append(["A"])
        ws2.append(["B"])
        wb2.save(second_file)
        with second_file.open("rb") as fh:
            trans_resp = call("POST", "/api/excel/upload", token=owner_token,
                              files={"file": (second_file.name, fh, "application/octet-stream")},
                              timeout=60)
        trans_body = payload_of(trans_resp)
        trans_doc = (trans_body or {}).get("data", {}).get("documentId")
        excel_ids.append(trans_doc)
        mark("excel", "second upload for transfer", trans_doc is not None)
        if trans_doc:
            check_json("excel", "transfer owner to member", "PUT",
                       f"/api/excel/document/{trans_doc}/transfer", token=owner_token,
                       json_body={"userId": member1_id})
            back_resp = call("PUT", f"/api/excel/document/{trans_doc}/transfer",
                             token=member1_token, json_body={"userId": owner_id})
            back_body = payload_of(back_resp)
            mark("excel", "transfer owner back", back_resp.status_code == 200
                 and isinstance(back_body, dict) and back_body.get("success") is not False)

        # ---- trash / restore / purge
        excel_ids = [i for i in excel_ids if i is not None]
        check_json("excel", "batch delete to recycle", "POST",
                   "/api/excel/document/batch-delete", token=owner_token,
                   json_body={"ids": excel_ids})
        recycle_body = payload_of(call("GET", "/api/excel/document/recycle",
                                       token=owner_token, params={"pageNum": 1,
                                                                  "pageSize": 50}))
        recycle_ids = {r.get("id") for r in ((recycle_body or {}).get("data", {}).get("records") or [])}
        mark("excel", "recycle list", str(excel_doc_id) in {str(i) for i in recycle_ids})
        check_json("excel", "batch restore", "POST", "/api/excel/document/batch-restore",
                   token=owner_token, json_body={"ids": excel_ids})
        check_json("excel", "batch delete again", "POST",
                   "/api/excel/document/batch-delete", token=owner_token,
                   json_body={"ids": excel_ids})
        check_json("excel", "batch purge", "POST", "/api/excel/document/batch-purge",
                   token=owner_token, json_body={"ids": excel_ids})
        check_json("excel", "clear recycle", "POST", "/api/excel/document/recycle/clear",
                   token=owner_token)

    # -------------------------------------------------------- admin / ops
    print("\n-- admin ops & metrics --")
    check_json("admin ops", "summary", "GET", "/api/admin/ops/summary", token=admin_token)
    check_json("admin ops", "audit page", "GET", "/api/admin/ops/audit", token=admin_token)
    check_json("admin ops", "collab page", "GET", "/api/admin/ops/collab", token=admin_token)
    check_json("admin ops", "rooms page", "GET", "/api/admin/ops/rooms", token=admin_token)
    check_json("admin ops", "version policy", "GET", "/api/admin/ops/version-policy",
               token=admin_token)
    check_json("admin ops", "update version policy", "PUT",
               "/api/admin/ops/version-policy", token=admin_token,
               json_body={"retentionCount": 30, "autoCellIntervalMinutes": 2},
               accept_error_body=True)
    check_json("admin ops", "version stats", "GET", "/api/admin/ops/version-stats",
               token=admin_token)
    check_json("admin metrics", "overview", "GET", "/api/admin/metrics/overview",
               token=admin_token)
    check_json("admin metrics", "platform stats", "GET", "/api/admin/metrics/platform",
               token=admin_token, params={"period": "LAST_30_DAYS"})
    check_json("admin metrics", "user stats", "GET", f"/api/admin/metrics/user/{owner_id}",
               token=admin_token, params={"period": "LAST_30_DAYS"})
    check_json("admin metrics", "group stats", "GET", f"/api/admin/metrics/group/{group_id}",
               token=admin_token, params={"period": "LAST_30_DAYS"})
    check_json("admin metrics", "trend", "GET", "/api/admin/metrics/trend",
               token=admin_token, params={"period": "LAST_30_DAYS", "module": "QA"})
    check_json("admin metrics", "user rank", "GET", "/api/admin/metrics/rank/users",
               token=admin_token)
    check_json("admin metrics", "group rank", "GET", "/api/admin/metrics/rank/groups",
               token=admin_token)

    # ----------------------------------------------------------- cleanup
    for doc_id in [doc_id, chunk_doc_id]:
        if doc_id:
            del_resp = call("DELETE", f"/api/documents/{doc_id}",
                            token=owner_token, params={"groupId": group_id})
            mark("cleanup", f"delete knowledge doc {doc_id}", del_resp.status_code == 200)

    return finish()


def parse_sse(resp):
    events = {}
    current = None
    token_count = 0
    try:
        for raw in resp.iter_lines(decode_unicode=True):
            if not raw:
                continue
            if raw.startswith("event:"):
                current = raw[6:].strip()
            elif raw.startswith("data:"):
                events.setdefault(current, []).append(raw[5:].strip())
                if current == "token":
                    token_count += 1
    except Exception as exc:
        events.setdefault("wireError", []).append(str(exc))
    return events, token_count


def check(category: str, name: str, ok: bool, detail: str = "") -> None:
    mark(category, name, bool(ok), detail)


def check_json_only(category: str, name: str, method: str, path: str,
                    token: str | None = None, expected_status: int = 200,
                    json_body: object | None = None, data: dict | None = None,
                    files: dict | None = None, params: dict | None = None,
                    timeout: int = 90, accept_error: bool = False):
    return check_json(category, name, method, path, token=token,
                      expected_status=expected_status, json_body=json_body,
                      data=data, files=files, params=params, timeout=timeout,
                      accept_error_body=accept_error)


# aliases used throughout main()
check = check
check_json = check_json
sha256_bytes = sha256_bytes


def preview_json(category: str, name: str, method: str, path: str,
                 token: str | None = None, params: dict | None = None):
    resp = call(method, path, token=token, params=params, timeout=90)
    body = payload_of(resp)
    ok = resp.status_code == 200 and isinstance(body, dict)
    mark(category, name, ok, f"http={resp.status_code}" if not ok else "")
    return body


def finish() -> int:
    failed = [r for r in results if not r[2]]
    print("\n== SUMMARY ==")
    total = len(results)
    passed = total - len(failed)
    print(f"TOTAL={total} PASS={passed} FAIL={len(failed)}")
    for category, name, _ok, detail in failed:
        print(f"  FAILED - {category} :: {name} - {detail}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
