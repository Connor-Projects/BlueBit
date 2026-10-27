"""gattlink framing model (1-byte prefix + IPv4 payload).

Basis (observed, documented in the repository):
  ``inspire3_decryption_analysis/RECONSTRUCTION_VALIDATION.md`` lines 51-64:

    * every ATT data-PDU value begins with a 1-byte control/sequence prefix;
    * ``prefix >= 0x80`` marks a **control** PDU:
        - ``0x80``  empty link-control event;
        - ``0x81``  MTU advertisement, payload ``00 00 08 08`` (big-endian 2056);
    * data PDUs carry ``prefix < 0x80`` followed by IPv4 bytes;
    * reassembly strips the prefix per data PDU, concatenates per direction, and
      slices on the IPv4 **total-length** field.

This module is a *model* of that shape. It is byte-accurate for the framing
rules above, but it is not a re-implementation of ``libxp.so``.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Tuple

MTU_ADVERT_PAYLOAD: bytes = bytes.fromhex("00000808")  # 2056, big-endian


def is_control_prefix(prefix: int) -> bool:
    return prefix >= 0x80


def frame_data(ipv4: bytes, prefix: int = 0x00) -> bytes:
    """Wrap IPv4 bytes in a data PDU (``prefix < 0x80``)."""
    if not (0x00 <= prefix < 0x80):
        raise ValueError("data PDU prefix must be < 0x80")
    return bytes([prefix]) + ipv4


def frame_control_mtu() -> bytes:
    """The observed MTU advertisement control PDU ``81 00 00 08 08``."""
    return bytes([0x81]) + MTU_ADVERT_PAYLOAD


def frame_control_empty() -> bytes:
    """The observed empty link-control PDU ``80``."""
    return bytes([0x80])


def deframe(pdu: bytes) -> Tuple[Optional[int], bytes]:
    """Return ``(prefix, payload)``; for control PDUs the payload is as-is."""
    if not pdu:
        return None, b""
    return pdu[0], pdu[1:]


def ipv4_total_length(ipv4: bytes) -> int:
    if len(ipv4) < 4:
        raise ValueError("IPv4 header too short")
    return int.from_bytes(ipv4[2:4], "big")


def reassemble(pdus: List[bytes]) -> List[bytes]:
    """Strip data-PDU prefixes, concatenate, and slice on IPv4 total length.

    Control PDUs (``prefix >= 0x80``) are ignored; zero-length data PDUs are
    treated as ACKs and contribute nothing.
    """
    stream = bytearray()
    for pdu in pdus:
        prefix, payload = deframe(pdu)
        if prefix is None or is_control_prefix(prefix):
            continue
        stream += payload

    packets: List[bytes] = []
    buf = bytes(stream)
    offset = 0
    while offset < len(buf):
        if len(buf) - offset < 4:
            break
        total = ipv4_total_length(buf[offset:])
        if total <= 0 or offset + total > len(buf):
            break
        packets.append(buf[offset:offset + total])
        offset += total
    return packets


@dataclass(frozen=True)
class FramedStream:
    """A sequence of data PDUs that reassembles to exactly one IPv4 packet."""

    pdus: Tuple[bytes, ...]

    def reassembled(self) -> List[bytes]:
        return reassemble(list(self.pdus))


def to_data_pdus(ipv4: bytes, chunk: int = 0, prefix: int = 0x00) -> FramedStream:
    """Split one IPv4 packet across data PDUs (single PDU by default)."""
    if chunk <= 0:
        return FramedStream((frame_data(ipv4, prefix),))
    pdus = [
        frame_data(ipv4[i:i + chunk], prefix)
        for i in range(0, len(ipv4), chunk)
    ]
    return FramedStream(tuple(pdus))
