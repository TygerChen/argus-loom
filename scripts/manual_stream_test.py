import time

import requests

BASE = "http://localhost:10001"


def main():
    login = requests.post(BASE + "/api/auth/login", json={"loginId": "admin", "password": "admin123"}, timeout=15)
    print("login", login.status_code)
    suffix = str(int(time.time() * 1000))
    username = f"stream_{suffix}"
    register = requests.post(
        BASE + "/api/auth/register",
        json={
            "username": username,
            "email": f"{username}@test.local",
            "displayName": "Stream Test",
            "password": "StreamPass1!",
        },
        timeout=15,
    )
    print("register", register.status_code, register.text[:120])
    login = requests.post(BASE + "/api/auth/login", json={"loginId": username, "password": "StreamPass1!"}, timeout=15)
    token = login.json()["data"]["accessToken"]
    headers = {"Authorization": f"Bearer {token}"}
    session_resp = requests.post(BASE + "/api/assistant/sessions", headers=headers, json={}, timeout=30)
    print("session", session_resp.status_code, session_resp.text[:200])
    session = session_resp.json()["data"]
    session_id = session.get("sessionId") or session.get("id")
    stream = requests.post(
        BASE + "/api/assistant/chat/stream",
        headers=headers,
        json={"sessionId": session_id, "message": "Say hi", "toolMode": "CHAT"},
        stream=True,
        timeout=90,
    )
    print("stream", stream.status_code, dict(stream.headers))
    count = 0
    try:
        for line in stream.iter_lines(decode_unicode=True):
            count += 1
            if count <= 12:
                print("LINE", repr(line))
        print("finished lines", count)
    except Exception as exc:
        print("EXC", type(exc).__name__, str(exc), "lines", count)


if __name__ == "__main__":
    main()
