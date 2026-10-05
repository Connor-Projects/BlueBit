#!/usr/bin/env python3
"""
Fitbit Local Pairing Backend Mock Server

Implements pairing-related endpoints discovered in Fitbit APK analysis.
Runs on localhost and logs all requests for analysis.

Endpoints:
  POST /1/devices/client/tracker/data/validate.json
  POST /1/devices/client/tracker/data/pair.json
  POST /1/devices/client/tracker/data/ack.json
  POST /1/devices/client/tracker/data/sync.json
  POST /1/devices/client/tracker/data/sync/app.json
  POST /1/devices/client/tracker/data/whitelabel-pair.json

Usage:
  python fitbit_mock_server.py [--port 8080] [--host 0.0.0.0]

From Android emulator, use http://10.0.2.2:<port>/
From physical device on same LAN, use http://<host-ip>:<port>/
"""

import argparse
import base64
import json
import logging
import sys
import time
from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlparse

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(message)s",
    datefmt="%H:%M:%S"
)
logger = logging.getLogger("fitbit-mock")

# Default response configuration
CONFIG = {
    "validate": {
        "pairingToken": "mock-pairing-token-00000000000000000000",
        "peripheralDeviceType": "Inspire 3",
        # Fitbit-Tracker-Challenge header values (bitmask of sxn enum)
        # 0 = no challenges, 1 = challenge 0, 2 = challenge 1, etc.
        "challenge_mask": 0,
    },
    "pair": {
        # Base64-encoded minimal protobuf response.
        # In real flow, this is a protobuf-encoded binary blob from the server
        # that contains device configuration/data. Here we return a minimal
        # placeholder that the client can decode without crashing.
        "response_base64": base64.b64encode(b"\x00" * 16).decode("ascii"),
    },
    "ack": {
        "status": "ok",
    },
    "sync": {
        # Base64-encoded minimal sync response
        "response_base64": base64.b64encode(b"\x00" * 32).decode("ascii"),
    },
    "sync_app": {
        "response_base64": base64.b64encode(b"\x00" * 16).decode("ascii"),
    },
    "whitelabel_pair": {
        "response_base64": base64.b64encode(b"\x00" * 16).decode("ascii"),
    },
}

REQUEST_LOG = []


class FitbitMockHandler(BaseHTTPRequestHandler):
    """HTTP request handler for Fitbit mock endpoints."""

    def log_message(self, format, *args):
        # Suppress default logging; we use structured logging
        pass

    def _send_json(self, status_code, data, extra_headers=None):
        body = json.dumps(data, indent=2).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        if extra_headers:
            for key, value in extra_headers.items():
                self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def _send_text(self, status_code, text, extra_headers=None):
        body = text.encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        if extra_headers:
            for key, value in extra_headers.items():
                self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/" or path == "/status":
            self._send_json(200, {
                "status": "running",
                "endpoints": [
                    "POST /1/devices/client/tracker/data/validate.json",
                    "POST /1/devices/client/tracker/data/pair.json",
                    "POST /1/devices/client/tracker/data/ack.json",
                    "POST /1/devices/client/tracker/data/sync.json",
                    "POST /1/devices/client/tracker/data/sync/app.json",
                    "POST /1/devices/client/tracker/data/whitelabel-pair.json",
                ],
                "request_count": len(REQUEST_LOG),
                "config": CONFIG,
            })
            return

        if path == "/log":
            self._send_json(200, {"requests": REQUEST_LOG})
            return

        logger.warning(f"GET {path} -> 404")
        self._send_json(404, {"error": "Not found", "path": path})

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)

        # Read body
        content_length = int(self.headers.get("Content-Length", 0))
        body_bytes = self.rfile.read(content_length) if content_length > 0 else b""

        # Try to decode body as text for logging
        try:
            body_text = body_bytes.decode("utf-8")
        except UnicodeDecodeError:
            body_text = base64.b64encode(body_bytes).decode("ascii")

        entry = {
            "timestamp": datetime.utcnow().isoformat() + "Z",
            "method": "POST",
            "path": path,
            "query": {k: v[0] if len(v) == 1 else v for k, v in query.items()},
            "headers": dict(self.headers),
            "body_preview": body_text[:500] if len(body_text) <= 500 else body_text[:500] + "...",
            "body_length": len(body_bytes),
        }
        REQUEST_LOG.append(entry)

        logger.info(f"POST {path}")
        logger.info(f"  Query: {entry['query']}")
        logger.info(f"  Body: {entry['body_preview']}")

        # Route to handler
        if path == "/1/devices/client/tracker/data/validate.json":
            self._handle_validate(entry)
        elif path == "/1/devices/client/tracker/data/pair.json":
            self._handle_pair(entry)
        elif path == "/1/devices/client/tracker/data/ack.json":
            self._handle_ack(entry)
        elif path == "/1/devices/client/tracker/data/sync.json":
            self._handle_sync(entry)
        elif path == "/1/devices/client/tracker/data/sync/app.json":
            self._handle_sync_app(entry)
        elif path == "/1/devices/client/tracker/data/whitelabel-pair.json":
            self._handle_whitelabel_pair(entry)
        else:
            logger.warning(f"POST {path} -> 404")
            self._send_json(404, {"error": "Not found", "path": path})

    def _handle_validate(self, entry):
        """
        POST /1/devices/client/tracker/data/validate.json
        Query: btleName, secret, btAddress
        Body: base64-encoded device data (from tracker)
        Response: JSON {pairingToken, peripheralDeviceType}
        Headers: Fitbit-Tracker-Challenge (if challenges required)
        """
        cfg = CONFIG["validate"]
        extra_headers = {}

        # If challenge_mask > 0, add Fitbit-Tracker-Challenge header
        if cfg.get("challenge_mask", 0) > 0:
            extra_headers["Fitbit-Tracker-Challenge"] = str(cfg["challenge_mask"])

        response = {
            "pairingToken": cfg["pairingToken"],
            "peripheralDeviceType": cfg["peripheralDeviceType"],
        }

        logger.info(f"  -> validate response: {response}")
        self._send_json(200, response, extra_headers)

    def _handle_pair(self, entry):
        """
        POST /1/devices/client/tracker/data/pair.json
        Query: pairingToken, challengesRun, challengeResults, maxCommsVersion
        Body: base64-encoded device data
        Response: base64-encoded binary (server-derived device config)
        """
        cfg = CONFIG["pair"]
        logger.info(f"  -> pair response: base64 blob ({len(cfg['response_base64'])} chars)")
        self._send_text(200, cfg["response_base64"])

    def _handle_ack(self, entry):
        """
        POST /1/devices/client/tracker/data/ack.json
        Query: ackToken, challengesRun, challengeResults
        Body: (empty or device data)
        Response: minimal ack
        """
        cfg = CONFIG["ack"]
        logger.info(f"  -> ack response: {cfg}")
        self._send_json(200, cfg)

    def _handle_sync(self, entry):
        """
        POST /1/devices/client/tracker/data/sync.json
        Query: trigger, btleName, maxCommsVersion, [includeApps, limitAppSize]
        Body: base64-encoded device data (mega dump)
        Response: base64-encoded sync data
        """
        cfg = CONFIG["sync"]
        logger.info(f"  -> sync response: base64 blob ({len(cfg['response_base64'])} chars)")
        self._send_text(200, cfg["response_base64"])

    def _handle_sync_app(self, entry):
        """
        POST /1/devices/client/tracker/data/sync/app.json
        Query: trigger, btleName, maxCommsVersion, mediaEvent, req
        Body: base64-encoded device data
        Response: base64-encoded app sync data
        """
        cfg = CONFIG["sync_app"]
        logger.info(f"  -> sync/app response: base64 blob ({len(cfg['response_base64'])} chars)")
        self._send_text(200, cfg["response_base64"])

    def _handle_whitelabel_pair(self, entry):
        """
        POST /1/devices/client/tracker/data/whitelabel-pair.json
        Query: maxCommsVersion
        Body: device data
        Response: base64-encoded binary
        """
        cfg = CONFIG["whitelabel_pair"]
        logger.info(f"  -> whitelabel-pair response: base64 blob ({len(cfg['response_base64'])} chars)")
        self._send_text(200, cfg["response_base64"])


def run_server(host, port):
    server = HTTPServer((host, port), FitbitMockHandler)
    logger.info(f"Fitbit Mock Server running on http://{host}:{port}")
    logger.info("Endpoints:")
    logger.info("  POST /1/devices/client/tracker/data/validate.json")
    logger.info("  POST /1/devices/client/tracker/data/pair.json")
    logger.info("  POST /1/devices/client/tracker/data/ack.json")
    logger.info("  POST /1/devices/client/tracker/data/sync.json")
    logger.info("  POST /1/devices/client/tracker/data/sync/app.json")
    logger.info("  POST /1/devices/client/tracker/data/whitelabel-pair.json")
    logger.info("GET  /status  -> server status")
    logger.info("GET  /log     -> request log")
    logger.info("Press Ctrl+C to stop")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("Shutting down...")
        server.shutdown()


def main():
    parser = argparse.ArgumentParser(description="Fitbit Local Pairing Backend Mock Server")
    parser.add_argument("--host", default="0.0.0.0", help="Bind host (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8080, help="Bind port (default: 8080)")
    args = parser.parse_args()
    run_server(args.host, args.port)


if __name__ == "__main__":
    main()
