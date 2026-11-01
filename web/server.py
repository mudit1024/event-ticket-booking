#!/usr/bin/env python3
"""Serve the Encore UI and proxy its API requests to local Spring services."""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import os

ROOT = Path(__file__).resolve().parent
TARGETS = {"/api/catalog": os.getenv("CATALOG_URL", "http://localhost:8081"),
           "/api/booking": os.getenv("BOOKING_URL", "http://localhost:8083")}


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def do_GET(self):
        if self.path.startswith("/api/catalog"):
            self.proxy(TARGETS["/api/catalog"], self.path[len("/api/catalog"):])
        elif self.path.startswith("/api/inventory"):
            self.proxy(os.getenv("INVENTORY_URL", "http://localhost:8082"), self.path[len("/api/inventory"):])
        elif self.path.startswith("/api/booking"):
            self.proxy(TARGETS["/api/booking"], self.path[len("/api/booking"):])
        else:
            super().do_GET()

    def do_POST(self):
        if self.path.startswith("/api/inventory"):
            self.proxy(os.getenv("INVENTORY_URL", "http://localhost:8082"), self.path[len("/api/inventory"):])
        elif self.path.startswith("/api/booking"):
            self.proxy(TARGETS["/api/booking"], self.path[len("/api/booking"):])
        else:
            self.send_error(404)

    def proxy(self, base, path):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0"))) if self.command == "POST" else None
        headers = {key: value for key, value in self.headers.items()
                   if key.lower() in ("content-type", "idempotency-key", "x-correlation-id")}
        request = Request(base + path, data=body, headers=headers, method=self.command)
        try:
            with urlopen(request, timeout=30) as response:
                data, status = response.read(), response.status
        except HTTPError as error:
            data, status = error.read(), error.code
        except (URLError, TimeoutError) as error:
            data, status = ('{"message":"Backend service unavailable: %s"}' % error).encode(), 502
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    port = int(os.getenv("UI_PORT", "3000"))
    print(f"Encore UI: http://localhost:{port} (catalog {TARGETS['/api/catalog']}, booking {TARGETS['/api/booking']})")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
