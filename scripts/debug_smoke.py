import io
import time

import requests

BASE = "http://localhost:10001"


def body_of(resp):
    try:
        return resp.json()
    except Exception:
        return resp.text[:500]


def login(user, password):
    r = requests.post(BASE + "/api/auth/login",
                      json={"loginId": user, "password": password}, timeout=20)
    print("LOGIN", user, r.status_code, body_of(r))
    return r.json()["data"]["accessToken"]


owner = "smoke_1789616256847_owner"
owner_token = login(owner, "SmokePass1!New1")
admin_token = login("admin", "admin123")
admin_headers = {"Authorization": "Bearer " + admin_token}

r = requests.get(BASE + "/api/admin/users", headers=admin_headers, timeout=20)
b = body_of(r)
print("ADMIN USERS", r.status_code, type(b).__name__)
if isinstance(b, dict):
    data = b.get("data")
    print("DATA_TYPE", type(data).__name__, "COUNT", len(data) if isinstance(data, list) else "-")
    if isinstance(data, list):
        print("SAMPLE", data[0])

new_pass = "SmokePass1!New1"
r = requests.post(
    BASE + "/api/account/change-password",
    headers={"Authorization": "Bearer " + owner_token},
    json={"currentPassword": "SmokePass1!", "newPassword": new_pass},
    timeout=20,
)
print("CHANGE_PASS_NEW", r.status_code, body_of(r))

reg_user = "dis_" + str(int(time.time() * 1000))
reg_email = reg_user + "@test.local"
r = requests.post(
    BASE + "/api/auth/register",
    json={"username": reg_user, "email": reg_email, "displayName": "Disabled Test",
          "password": "SmokePass1!"},
    timeout=20,
)
print("REGISTER", r.status_code, body_of(r))

users = requests.get(BASE + "/api/admin/users", headers=admin_headers, timeout=20).json()
uid = None
for row in users.get("data") or []:
    if row.get("username") == reg_user:
        uid = row.get("id") or row.get("userId")
print("NEW_USER_ID", uid)
if uid:
    r = requests.patch(
        BASE + f"/api/admin/users/{uid}/status", headers=admin_headers,
        json={"status": "DISABLED"}, timeout=20,
    )
    print("DISABLE", r.status_code, body_of(r))
    d_token = None
    try:
        d_token = login(reg_user, "SmokePass1!")
    except Exception as exc:
        print("DISABLED_LOGIN_EXPECTED_FAIL", exc)

# Chunk upload debug
group_id = 7
content = ("chunk payload " * 2000 + "END").encode()
chunk_count = 2
chunk_size = (len(content) + 1) // chunk_count
init = requests.post(
    BASE + "/api/documents/upload/init",
    headers={"Authorization": "Bearer " + owner_token},
    json={
        "groupId": group_id,
        "fileName": "dbg.txt",
        "fileSize": len(content),
        "contentType": "text/plain",
        "fileHash": __import__("hashlib").sha256(content).hexdigest(),
        "chunkSize": chunk_size,
        "chunkCount": chunk_count,
    },
    timeout=30,
)
init_json = body_of(init)
print("INIT", init.status_code, init_json)
upload_id = (init_json or {}).get("data", {}).get("uploadId")
if upload_id:
    for idx in range(chunk_count):
        part = content[idx * chunk_size:(idx + 1) * chunk_size]
        resp = requests.post(
            BASE + "/api/documents/upload/chunks",
            headers={"Authorization": "Bearer " + owner_token},
            data={"uploadId": upload_id, "chunkIndex": str(idx),
                  "chunkHash": __import__("hashlib").sha256(part).hexdigest()},
            files={"chunk": (f"p{idx}", io.BytesIO(part), "application/octet-stream")},
            timeout=60,
        )
        print("CHUNK", idx, resp.status_code, body_of(resp))
    status = requests.get(
        BASE + f"/api/documents/upload/{upload_id}",
        headers={"Authorization": "Bearer " + owner_token}, timeout=30,
    )
    print("STATUS", status.status_code, body_of(status))
    done = requests.post(
        BASE + f"/api/documents/upload/{upload_id}/complete",
        headers={"Authorization": "Bearer " + owner_token}, timeout=60,
    )
    print("COMPLETE", done.status_code, body_of(done))

# QA ask debug
qa = requests.post(
    BASE + "/api/qa/ask",
    headers={"Authorization": "Bearer " + owner_token},
    json={"groupId": group_id, "question": "What is in the smoke document?"},
    timeout=180,
)
print("QA_ASK", qa.status_code, body_of(qa))

# SSE stream debug
with requests.post(
    BASE + "/api/qa/stream-ask",
    headers={"Authorization": "Bearer " + owner_token},
    json={"groupId": group_id, "question": "What is in the smoke document?"},
    timeout=180,
    stream=True,
) as resp:
    print("SSE_HTTP", resp.status_code, resp.headers.get("content-type"))
    for idx, raw in enumerate(resp.iter_lines(decode_unicode=True)):
        print("SSE", idx, raw)
        if idx > 40:
            break
