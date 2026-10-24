#!/usr/bin/env python3
"""OoklaServer-like HTTP endpoints for MySignal (hello / download?size= / upload)."""

from __future__ import annotations

import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HOST = os.environ.get("SPEEDTEST_HOST", "0.0.0.0")
PORT = int(os.environ.get("PORT", os.environ.get("SPEEDTEST_PORT", "8099")))
SERVER_NAME = os.environ.get("SPEEDTEST_SERVER_NAME", "mysignal-azure")
REGION = os.environ.get("SPEEDTEST_REGION", "southeastasia")
VERSION = "1.0.0"
CHUNK = 64 * 1024
DEFAULT_DOWNLOAD = 25_000_000
MAX_DOWNLOAD = int(os.environ.get("SPEEDTEST_MAX_DOWNLOAD", str(128 * 1024 * 1024)))
MAX_UPLOAD = int(os.environ.get("SPEEDTEST_MAX_BODY", str(16 * 1024 * 1024)))
FILL = os.urandom(CHUNK)


def normalize_path(raw: str) -> str:
    path = urlparse(raw).path.rstrip("/") or "/"
    if path == "/speedtest":
        return "/hello"
    if path.startswith("/speedtest/"):
        rest = path[len("/speedtest") :]
        return rest if rest.startswith("/") else "/" + rest
    return path


class SpeedtestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "MySignalSpeedtest/" + VERSION

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _cors(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        path = normalize_path(self.path)
        if path in ("/", "/hello"):
            self._hello()
            return
        if path == "/download":
            self._download()
            return
        self.send_error(404)

    def do_POST(self) -> None:  # noqa: N802
        path = normalize_path(self.path)
        if path == "/upload":
            self._upload()
            return
        self.send_error(404)

    def _client_ip(self) -> str:
        forwarded = (self.headers.get("X-Forwarded-For") or "").split(",")[0].strip()
        if forwarded:
            return forwarded
        real = (self.headers.get("X-Real-IP") or "").strip()
        if real:
            return real
        return self.client_address[0] if self.client_address else ""

    def _hello(self) -> None:
        payload = {
            "hello": True,
            "server": SERVER_NAME,
            "region": REGION,
            "version": VERSION,
            "client_ip": self._client_ip(),
        }
        body = b"hello\n" + json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Server", SERVER_NAME)
        self.send_header("X-Client-IP", payload["client_ip"])
        self._cors()
        self.end_headers()
        self.wfile.write(body)

    def _download(self) -> None:
        query = parse_qs(urlparse(self.path).query)
        raw = (query.get("size") or ["%d" % DEFAULT_DOWNLOAD])[0]
        try:
            size = int(raw)
        except ValueError:
            self.send_error(400, "invalid size")
            return
        if size < 0 or size > MAX_DOWNLOAD:
            self.send_error(400, "size out of range")
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(size))
        self._cors()
        self.end_headers()
        remaining = size
        while remaining > 0:
            chunk = FILL if remaining >= len(FILL) else FILL[:remaining]
            self.wfile.write(chunk)
            remaining -= len(chunk)

    def _upload(self) -> None:
        received = _discard_body(self)
        body = b"ok"
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Received-Bytes", str(received))
        self._cors()
        self.end_headers()
        self.wfile.write(body)


def _discard_body(handler: BaseHTTPRequestHandler) -> int:
    length_header = handler.headers.get("Content-Length")
    received = 0
    if length_header:
        try:
            remaining = int(length_header)
        except ValueError:
            remaining = 0
        if remaining < 0:
            remaining = 0
        remaining = min(remaining, MAX_UPLOAD)
        while remaining > 0:
            chunk = handler.rfile.read(min(CHUNK, remaining))
            if not chunk:
                break
            received += len(chunk)
            remaining -= len(chunk)
        return received
    while received < MAX_UPLOAD:
        chunk = handler.rfile.read(CHUNK)
        if not chunk:
            break
        received += len(chunk)
    return received


def make_server(host: str = HOST, port: int = PORT) -> ThreadingHTTPServer:
    return ThreadingHTTPServer((host, port), SpeedtestHandler)


def main() -> None:
    httpd = make_server(HOST, PORT)
    print("mysignal-speedtest listening on http://%s:%s/speedtest/hello" % (HOST, PORT), flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
