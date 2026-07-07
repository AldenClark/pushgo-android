#!/usr/bin/env python3

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


CHANNELS = {
    "01HX5ECRN7FY2Y8A4K6M9PQRSV": {"channel_name": "Ops Alerts", "password": "ops-alerts-pass"},
    "01HX5ECS57Q2X4A6K8M9NPRTVW": {"channel_name": "Ops Assets", "password": "ops-assets-pass"},
    "01HX5ECT9K7R2Y4A6M8NPQRSVW": {"channel_name": "Secure Notify", "password": "secure-notify-pass"},
}

DEVICE_KEY = "a11y-mock-device-key"


class Handler(BaseHTTPRequestHandler):
    server_version = "PushGoA11yMock/1.0"

    def _json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _success(self, data: dict | None = None, status: int = 200) -> None:
        self._json(status, {"success": True, "data": data or {}})

    def _error(
        self,
        status: int,
        code: str,
        detail: str,
        *,
        category: str = "validation",
        title: str = "Request failed",
        retryable: bool = False,
    ) -> None:
        self._json(
            status,
            {
                "success": False,
                "error_code": code,
                "error": detail,
                "problem": {
                    "code": code,
                    "category": category,
                    "status": status,
                    "title": title,
                    "detail": detail,
                    "localized_message": detail,
                    "locale": "en",
                    "retryable": retryable,
                    "request_id": "pushgo-a11y-mock",
                },
            },
        )

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/healthz":
            self._json(200, {"ok": True})
            return
        if parsed.path == "/channel/exists":
            channel_id = parse_qs(parsed.query).get("channel_id", [""])[0].strip()
            entry = CHANNELS.get(channel_id)
            self._success(
                {
                    "exists": entry is not None,
                    "channel_name": entry["channel_name"] if entry else "",
                },
            )
            return
        self._json(404, {"error": "not_found", "path": parsed.path})

    def do_POST(self) -> None:
        payload = self._read_json()
        path = urlparse(self.path).path

        if path in {
            "/device/register",
            "/channel/device",
            "/channel/device/provider-token/retire",
            "/channel/device/delete",
        }:
            self._success({"device_key": payload.get("device_key") or DEVICE_KEY})
            return

        if path == "/channel/sync":
            channels = []
            for channel_id, entry in sorted(CHANNELS.items()):
                channels.append(
                    {
                        "channel_id": channel_id,
                        "channel_name": entry["channel_name"],
                        "subscribed": True,
                    }
                )
            self._success({"success": len(channels), "failed": 0, "channels": channels})
            return

        if path == "/channel/rename":
            channel_id = payload.get("channel_id", "").strip()
            channel_name = payload.get("channel_name", "").strip()
            password = payload.get("password", "").strip()
            entry = CHANNELS.get(channel_id)
            if entry is None:
                self._error(404, "channel_not_found", "Channel not found", category="not_found")
                return
            if entry["password"] != password:
                self._error(
                    400,
                    "channel_password_mismatch",
                    "Channel password mismatch",
                )
                return
            entry["channel_name"] = channel_name or entry["channel_name"]
            self._success({"channel_id": channel_id, "channel_name": entry["channel_name"]})
            return

        if path == "/channel/unsubscribe":
            channel_id = payload.get("channel_id", "").strip()
            removed = CHANNELS.pop(channel_id, None) is not None
            self._success({"removed": removed})
            return

        if path == "/channel/subscribe":
            channel_id = payload.get("channel_id", "").strip()
            channel_name = payload.get("channel_name", "").strip()
            password = payload.get("password", "").strip()
            created = False
            if not channel_id:
                channel_id = (channel_name or "channel").lower().replace(" ", "-")
                created = True
            entry = CHANNELS.get(channel_id)
            if entry is None:
                CHANNELS[channel_id] = {
                    "channel_name": channel_name or channel_id,
                    "password": password,
                }
                created = True
            else:
                entry["password"] = password or entry["password"]
                if channel_name:
                    entry["channel_name"] = channel_name
            self._success(
                {
                    "created": created,
                    "subscribed": True,
                    "channel_id": channel_id,
                    "channel_name": CHANNELS[channel_id]["channel_name"],
                },
            )
            return

        self._json(404, {"error": "not_found", "path": path})

    def log_message(self, format: str, *args) -> None:
        return


def main() -> None:
    server = ThreadingHTTPServer(("127.0.0.1", 18080), Handler)
    print("PushGo accessibility mock gateway listening on http://127.0.0.1:18080", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
