#!/usr/bin/env python3
"""Offline CoAP message parser for BlueBit protocol prototyping.

Parses raw CoAP bytes (RFC 7252) and Block options (RFC 7959).
Works entirely offline on captured data.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple


COAP_OPTION_NAMES: Dict[int, str] = {
    1: "If-Match",
    3: "URI-Host",
    4: "ETag",
    5: "If-None-Match",
    6: "Observe",
    7: "URI-Port",
    8: "Location-Path",
    11: "URI-Path",
    12: "Content-Format",
    14: "Max-Age",
    15: "URI-Query",
    17: "Accept",
    20: "Location-Query",
    23: "Block2",
    27: "Block1",
    28: "Size2",
    35: "Proxy-URI",
    39: "Proxy-Scheme",
    60: "Size1",
}

COAP_CODE_NAMES: Dict[int, str] = {
    0x00: "0.00 Empty",
    0x01: "0.01 GET",
    0x02: "0.02 POST",
    0x03: "0.03 PUT",
    0x04: "0.04 DELETE",
    0x45: "2.05 Content",
    0x44: "2.04 Changed",
    0x5f: "2.31 Continue",
    0x80: "4.00 Bad Request",
    0x81: "4.01 Unauthorized",
    0x84: "4.04 Not Found",
    0x85: "4.05 Method Not Allowed",
}


@dataclass(frozen=True)
class BlockOption:
    """Decoded Block1/Block2 option value."""

    num: int
    m: int
    szx: int

    @property
    def size(self) -> int:
        return 2 ** (self.szx + 4)

    def __str__(self) -> str:
        more = ", M=1" if self.m else ", M=0"
        return f"block #{self.num}, size {self.size}{more}, szx={self.szx}"


@dataclass(frozen=True)
class CoapOption:
    number: int
    value: bytes

    @property
    def name(self) -> str:
        return COAP_OPTION_NAMES.get(self.number, f"Unknown({self.number})")

    def as_uri_path(self) -> str:
        if self.number == 11:
            return self.value.decode("utf-8", errors="replace")
        raise ValueError(f"Option {self.name} is not a URI-Path")

    def as_uri_query(self) -> str:
        if self.number == 15:
            return self.value.decode("utf-8", errors="replace")
        raise ValueError(f"Option {self.name} is not a URI-Query")

    def as_content_format(self) -> int:
        if self.number == 12:
            if len(self.value) == 0:
                return 0
            return int.from_bytes(self.value, "big")
        raise ValueError(f"Option {self.name} is not Content-Format")

    def as_block(self) -> BlockOption:
        if self.number not in (23, 27):
            raise ValueError(f"Option {self.name} is not a block option")
        val = int.from_bytes(self.value, "big")
        return BlockOption(num=val >> 4, m=(val >> 3) & 1, szx=val & 7)


@dataclass(frozen=True)
class CoapMessage:
    version: int
    type: int
    tkl: int
    code: int
    mid: int
    token: bytes
    options: List[CoapOption] = field(default_factory=list)
    payload: bytes = b""

    @property
    def type_name(self) -> str:
        return ["CON", "NON", "ACK", "RST"][self.type]

    @property
    def code_name(self) -> str:
        return COAP_CODE_NAMES.get(self.code, f"{self.code_class}.{self.code_detail:02d}")

    @property
    def code_class(self) -> int:
        return self.code >> 5

    @property
    def code_detail(self) -> int:
        return self.code & 0x1F

    @property
    def token_hex(self) -> str:
        return self.token.hex()

    def uri_path(self) -> str:
        parts = [opt.as_uri_path() for opt in self.options if opt.number == 11]
        return "/" + "/".join(parts) if parts else ""

    def uri_query(self) -> str:
        parts = [opt.as_uri_query() for opt in self.options if opt.number == 15]
        return "&".join(parts)

    def block2(self) -> Optional[BlockOption]:
        for opt in self.options:
            if opt.number == 23:
                return opt.as_block()
        return None

    def block1(self) -> Optional[BlockOption]:
        for opt in self.options:
            if opt.number == 27:
                return opt.as_block()
        return None

    def content_format(self) -> Optional[int]:
        for opt in self.options:
            if opt.number == 12:
                return opt.as_content_format()
        return None

    def summary(self) -> str:
        parts = [
            f"ver={self.version} {self.type_name} tkl={self.tkl}",
            f"code={self.code_name} mid=0x{self.mid:04x}",
            f"token={self.token_hex}",
        ]
        path = self.uri_path()
        if path:
            parts.append(f"path={path}")
        query = self.uri_query()
        if query:
            parts.append(f"query={query}")
        if self.block2():
            parts.append(f"Block2=({self.block2()})")
        if self.block1():
            parts.append(f"Block1=({self.block1()})")
        if self.content_format() is not None:
            parts.append(f"CF={self.content_format()}")
        parts.append(f"payload_len={len(self.payload)}")
        return " ".join(parts)


def _read_extended(offset: int, data: bytes, nibble: int) -> Tuple[int, int]:
    """Return (value, bytes_consumed) for an extended option delta/length."""
    if nibble < 13:
        return nibble, 0
    if nibble == 13:
        return 13 + data[offset], 1
    if nibble == 14:
        return 269 + int.from_bytes(data[offset : offset + 2], "big"), 2
    raise ValueError("Reserved option delta/length value 15")


def parse_coap(data: bytes) -> CoapMessage:
    """Parse a CoAP message from bytes."""
    if len(data) < 4:
        raise ValueError(f"CoAP message too short: {len(data)} bytes")

    version = (data[0] >> 6) & 0x03
    if version != 1:
        raise ValueError(f"Unsupported CoAP version: {version}")
    msg_type = (data[0] >> 4) & 0x03
    tkl = data[0] & 0x0F
    code = data[1]
    mid = int.from_bytes(data[2:4], "big")

    if len(data) < 4 + tkl:
        raise ValueError(f"CoAP message too short for token length {tkl}")
    token = data[4 : 4 + tkl]

    offset = 4 + tkl
    options: List[CoapOption] = []
    prev_number = 0
    payload = b""

    while offset < len(data):
        if data[offset] == 0xFF:
            payload = data[offset + 1 :]
            break

        delta_nibble = (data[offset] >> 4) & 0x0F
        length_nibble = data[offset] & 0x0F
        offset += 1

        delta, delta_ext = _read_extended(offset, data, delta_nibble)
        offset += delta_ext
        length, length_ext = _read_extended(offset, data, length_nibble)
        offset += length_ext

        if offset + length > len(data):
            raise ValueError("Option length exceeds message bounds")

        number = prev_number + delta
        value = data[offset : offset + length]
        options.append(CoapOption(number=number, value=value))
        prev_number = number
        offset += length

    return CoapMessage(
        version=version,
        type=msg_type,
        tkl=tkl,
        code=code,
        mid=mid,
        token=token,
        options=options,
        payload=payload,
    )


def parse_coap_hex(hex_str: str) -> CoapMessage:
    """Parse a CoAP message from a hex string (whitespace-insensitive)."""
    return parse_coap(bytes.fromhex(hex_str.replace(" ", "")))


if __name__ == "__main__":
    # Quick sanity check against the first captured sample.
    sample = "4403000087aacacfb47061697207646973706c6179d10306ff0801"
    msg = parse_coap_hex(sample)
    print(msg.summary())
