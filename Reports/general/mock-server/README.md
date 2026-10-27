# Fitbit Local Pairing Backend Mock Server

Lightweight Python mock server for Fitbit pairing endpoints. Runs with Python stdlib only — no pip dependencies.

## Quick Start

```bash
cd BlueBitApp/mock-server
python fitbit_mock_server.py --port 8080
```

From Android emulator, access via `http://10.0.2.2:8080/`  
From physical device on same LAN, use the host's LAN IP.

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/status` | Server status and config |
| GET | `/log` | Request log (all captured requests) |
| POST | `/1/devices/client/tracker/data/validate.json` | Returns `pairingToken` |
| POST | `/1/devices/client/tracker/data/pair.json` | Returns base64 binary blob |
| POST | `/1/devices/client/tracker/data/ack.json` | Returns `{"status": "ok"}` |
| POST | `/1/devices/client/tracker/data/sync.json` | Returns base64 binary blob |
| POST | `/1/devices/client/tracker/data/sync/app.json` | Returns base64 binary blob |
| POST | `/1/devices/client/tracker/data/whitelabel-pair.json` | Returns base64 binary blob |

## Configuration

Edit `CONFIG` dict in `fitbit_mock_server.py` to change responses:

```python
CONFIG = {
    "validate": {
        "pairingToken": "mock-pairing-token-00000000000000000000",
        "peripheralDeviceType": "Inspire 3",
        "challenge_mask": 0,  # Set >0 to add Fitbit-Tracker-Challenge header
    },
    "pair": {
        "response_base64": base64.b64encode(b"\x00" * 16).decode("ascii"),
    },
    ...
}
```

## Request Logging

Every request is logged to console and stored in memory. Access `GET /log` to retrieve the full request history with headers, query params, and body previews.

## BlueBit Integration

BlueBit currently does **not** make any HTTP calls. To use this mock server, BlueBit needs:

1. An HTTP client (Ktor client recommended for KMP)
2. Code to collect device data and call `validate.json`
3. Code to call `pair.json` with the returned token
4. Optionally: code to process the `pair` response and send data to the device

See `ENDPOINT_INVENTORY.md` for the full API specification.

## Security Note

This mock server is for **local experimentation only**. It does not implement real Fitbit authentication, does not validate secrets, and returns placeholder data. Do not expose it to the public internet.
