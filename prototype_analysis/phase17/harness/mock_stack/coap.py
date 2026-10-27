"""Minimal CoAP (RFC 7252) encoder for the harness, plus a re-export of the
repository's existing, unmodified parser.

Parsing is delegated to ``prototype_analysis/offline_poc/coap_parser.py`` so the
harness shares a single, already-golden-tested decoder. This module only adds an
encoder because the offline PoC is parse-only.
"""

from __future__ import annotations

import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

# Make the existing offline parser importable without modifying it.
# harness/mock_stack/coap.py -> parents[3] == prototype_analysis
_OFFLINE_POC = Path(__file__).resolve().parents[3] / "offline_poc"
if str(_OFFLINE_POC) not in sys.path:
    sys.path.insert(0, str(_OFFLINE_POC))

from coap_parser import parse_coap, parse_coap_hex  # noqa: E402  (re-exported)

# Message types
CON, NON, ACK, RST = 0, 1, 2, 3

# Request methods (code class 0)
GET, POST, PUT, DELETE = 0x01, 0x02, 0x03, 0x04

# Response codes: (class, detail) -> wire code byte
RESPONSE_CHANGED = (2, 4)          # 2.04
RESPONSE_CONTENT = (2, 5)          # 2.05
RESPONSE_CONTINUE = (2, 31)        # 2.31
RESPONSE_UNAUTHORIZED = (4, 1)     # 4.01
RESPONSE_FORBIDDEN = (4, 3)        # 4.03
RESPONSE_SERVICE_UNAVAILABLE = (5, 3)  # 5.03


def code_byte(response_code: Tuple[int, int]) -> int:
    """Wire code byte from a (class, detail) pair."""
    cls, detail = response_code
    return ((cls & 0x07) << 5) | (detail & 0x1F)


def code_name(response_code: Tuple[int, int]) -> str:
    cls, detail = response_code
    return f"{cls}.{detail:02d}"


METHOD_NAMES: Dict[int, str] = {GET: "GET", POST: "POST", PUT: "PUT", DELETE: "DELETE"}


@dataclass(frozen=True)
class ExtendedError:
    """Fitbit GoldenGate ``ExtendedError`` as modelled here.

    NOTE: modelled as an out-of-band field carried *alongside* a response. Its
    real on-wire encoding is not reproduced by this harness (see README).
    """

    code: int
    namespace: str = "AIRLINK_NAK"


@dataclass
class Response:
    status: Tuple[int, int]
    payload: bytes = b""
    extended_error: Optional[ExtendedError] = None

    @property
    def name(self) -> str:
        return code_name(self.status)


def _encode_extended(value: int) -> bytes:
    if value < 13:
        return bytes([value])
    if value < 269:
        return bytes([13, value - 13])
    return bytes([14]) + (value - 269).to_bytes(2, "big")


def _path_options(path: str) -> List[Tuple[int, bytes]]:
    segments = [s for s in path.split("/") if s]
    return [(11, s.encode("utf-8")) for s in segments]


def encode_request(
    path: str,
    method: int = GET,
    *,
    token: bytes = b"\x87\xaa\xca\xcf",
    mid: int = 0,
    msg_type: int = CON,
    payload: bytes = b"",
    extra_options: Sequence[Tuple[int, bytes]] = (),
) -> bytes:
    """Encode a CoAP request as bytes (no transport framing)."""
    options = _path_options(path) + list(extra_options)
    out = bytearray()
    out.append((1 << 6) | (msg_type << 4) | (len(token) & 0x0F))
    out.append(method & 0xFF)
    out += (mid & 0xFFFF).to_bytes(2, "big")
    out += token
    last = 0
    for number, value in sorted(options, key=lambda o: o[0]):
        delta = number - last
        last = number
        out += _encode_extended(delta)
        out += _encode_extended(len(value))
        out += value
    if payload:
        out.append(0xFF)
        out += payload
    return bytes(out)


def encode_response(
    response_code: Tuple[int, int],
    *,
    token: bytes = b"\x87\xaa\xca\xcf",
    mid: int = 0,
    msg_type: int = ACK,
    payload: bytes = b"",
) -> bytes:
    """Encode a CoAP response as bytes (no transport framing)."""
    out = bytearray()
    out.append((1 << 6) | (msg_type << 4) | (len(token) & 0x0F))
    out.append(code_byte(response_code))
    out += (mid & 0xFFFF).to_bytes(2, "big")
    out += token
    if payload:
        out.append(0xFF)
        out += payload
    return bytes(out)


__all__ = [
    "parse_coap",
    "parse_coap_hex",
    "encode_request",
    "encode_response",
    "ExtendedError",
    "Response",
    "code_byte",
    "code_name",
    "CON",
    "NON",
    "ACK",
    "RST",
    "GET",
    "POST",
    "PUT",
    "DELETE",
    "METHOD_NAMES",
    "RESPONSE_CHANGED",
    "RESPONSE_CONTENT",
    "RESPONSE_CONTINUE",
    "RESPONSE_UNAUTHORIZED",
    "RESPONSE_FORBIDDEN",
    "RESPONSE_SERVICE_UNAVAILABLE",
]
