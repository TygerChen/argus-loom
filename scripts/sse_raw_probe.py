import json
import socket
import time

import requests


def main():
    token, session_id = login_and_register()
    body = json.dumps({"sessionId": session_id, "message": "Say hi", "toolMode": "CHAT"}).encode()
    request = (
        b"POST /api/assistant/chat/stream HTTP/1.1\r\n"
        b"Host: localhost:10001\r\n"
        b"Authorization: Bearer " + token.encode() + b"\r\n"
        b"Content-Type: application/json\r\n"
        b"Accept: text/event-stream\r\n"
        b"Content-Length: " + str(len(body)).encode() + b"\r\n"
        b"Connection: close\r\n\r\n"
        + body
    )
    sock = socket.create_connection(("127.0.0.1", 10001), timeout=45)
    sock.sendall(request)
    data = b""
    while True:
        try:
            chunk = sock.recv(4096)
        except socket.timeout:
            print("TIMEOUT")
            break
        if not chunk:
            break
        data += chunk
    sock.close()
    print("total bytes", len(data))
    print("tail", repr(data[-120:]))
    print("has zero chunk", b"0\r\n\r\n" in data)


def login_and_register():
    suffix = str(int(time.time() * 1000))
    username = f"raw_{suffix}"
    requests.post(
        "http://localhost:10001/api/auth/register",
        json={
            "username": username,
            "email": f"{username}@example.com",
            "displayName": "Raw Probe",
            "password": "RawPass1!",
        },
        timeout=5,
    )
    token = requests.post(
        "http://localhost:10001/api/auth/login",
        json={"loginId": username, "password": "RawPass1!"},
        timeout=15,
    ).json()["data"]["accessToken"]
    session = requests.post(
        "http://localhost:10001/api/assistant/sessions",
        headers={"Authorization": f"Bearer {token}"},
        json={},
        timeout=15,
    ).json()["data"]
    session_id = session.get("sessionId") or session.get("id")
    return token, session_id


if __name__ == "__main__":
    main()
