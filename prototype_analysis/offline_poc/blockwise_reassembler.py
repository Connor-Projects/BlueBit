#!/usr/bin/env python3
"""Block-wise CoAP transfer reassembler.

Groups CoAP messages by URI-Path and reassembles payloads using Block2
(RFC 7959). Because CoAP responses do not repeat URI-Path options, callers
should supply a token->path map inferred from the request stream.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

from coap_parser import BlockOption, CoapMessage


@dataclass
class BlockRecord:
    number: int
    payload: bytes
    m: int
    szx: int
    source: str = ""


@dataclass
class ReassembledTransfer:
    path: str
    blocks: Dict[int, BlockRecord] = field(default_factory=dict)

    @property
    def is_complete(self) -> bool:
        if not self.blocks:
            return False
        max_block = max(self.blocks.keys())
        return self.blocks[max_block].m == 0 and set(range(max_block + 1)) == set(self.blocks.keys())

    @property
    def total_payload(self) -> bytes:
        if not self.blocks:
            return b""
        ordered = [self.blocks[n] for n in sorted(self.blocks)]
        return b"".join(b.payload for b in ordered)

    @property
    def missing_blocks(self) -> List[int]:
        if not self.blocks:
            return []
        max_block = max(self.blocks.keys())
        expected = set(range(max_block + 1))
        return sorted(expected - set(self.blocks.keys()))

    def summary(self) -> str:
        block_nums = sorted(self.blocks.keys())
        parts = [
            f"path={self.path}",
            f"blocks={block_nums}",
            f"complete={self.is_complete}",
        ]
        if self.missing_blocks:
            parts.append(f"missing={self.missing_blocks}")
        parts.append(f"total_payload_len={len(self.total_payload)}")
        return " ".join(parts)


def _extract_block(msg: CoapMessage) -> Optional[Tuple[int, int, int]]:
    """Return (block_num, m, szx) for a Block2 response, or None."""
    block = msg.block2()
    if block is not None:
        return block.num, block.m, block.szx
    # If the response has payload but no Block2, treat it as a single final block.
    if msg.payload:
        return 0, 0, 6
    return None


def reassemble_by_token(
    messages: List[Tuple[CoapMessage, str]]
) -> Dict[str, ReassembledTransfer]:
    """Reassemble block-wise transfers grouped by token.

    This works only when the response stream carries URI-Path options or when
    every block of a transfer shares the same token.
    """
    transfers: Dict[str, ReassembledTransfer] = {}

    for msg, source in messages:
        key = f"{msg.token_hex}:{msg.uri_path()}"
        if key not in transfers:
            transfers[key] = ReassembledTransfer(path=msg.uri_path())

        block_info = _extract_block(msg)
        if block_info is None:
            continue
        num, m, szx = block_info
        transfers[key].blocks[num] = BlockRecord(
            number=num, payload=msg.payload, m=m, szx=szx, source=source
        )

    return transfers


def reassemble_by_path(
    messages: List[Tuple[CoapMessage, str]],
    token_to_path: Dict[bytes, str],
    split_on_block_zero: bool = True,
) -> Dict[str, List[ReassembledTransfer]]:
    """Reassemble block-wise transfers grouped by inferred URI-Path.

    Args:
        messages: (CoAP message, source label) pairs. Usually response messages.
        token_to_path: Mapping from request token to URI-Path, inferred from the
            request stream.
        split_on_block_zero: If True, start a new transfer whenever a block #0
            is seen for the same path. This separates repeated operations on the
            same endpoint (e.g. the small /sync/dump?t=pair response from the
            main /sync/dump transfer).

    Returns:
        A dict mapping path -> list of ReassembledTransfer objects.
    """
    path_blocks: Dict[str, List[Tuple[int, int, int, bytes, str]]] = {}

    for msg, source in messages:
        path = token_to_path.get(msg.token, msg.uri_path())
        if not path:
            continue
        block_info = _extract_block(msg)
        if block_info is None:
            continue
        num, m, szx = block_info
        path_blocks.setdefault(path, []).append((num, m, szx, msg.payload, source))

    result: Dict[str, List[ReassembledTransfer]] = {}
    for path, blocks in path_blocks.items():
        # Sort by source frame number for stable ordering.
        def _frame_key(item: Tuple[int, int, int, bytes, str]) -> int:
            src = item[4]
            if src.startswith("frame "):
                try:
                    return int(src.split()[1].split()[0])
                except ValueError:
                    pass
            return 0

        blocks.sort(key=_frame_key)

        transfers: List[ReassembledTransfer] = []
        current: Optional[ReassembledTransfer] = None
        seen: set = set()

        for num, m, szx, payload, source in blocks:
            if current is None or (split_on_block_zero and num == 0):
                current = ReassembledTransfer(path=path)
                transfers.append(current)
                seen = set()
            if num in seen:
                continue
            seen.add(num)
            current.blocks[num] = BlockRecord(
                number=num, payload=payload, m=m, szx=szx, source=source
            )

        result[path] = transfers

    return result
