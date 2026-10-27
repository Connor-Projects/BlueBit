#!/usr/bin/env python3
"""Reconstruct the /sync/dump payload from captured CoAP samples.

Reads `decrypted_coap_samples.csv` for metadata and the corresponding
`sample_frame<N>_<dir>.bin` files for the full decrypted plaintext.

Important observation from the capture: CoAP responses do not repeat the
URI-Path options from the request. The PoC therefore builds a token->path map
from the request stream and uses it to label response blocks.
"""

from __future__ import annotations

import csv
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from coap_parser import CoapMessage, parse_coap


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
        # Complete if the highest block has M=0 and all lower blocks exist.
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


def load_all_messages(
    csv_path: Path, bin_dir: Path
) -> Tuple[List[Tuple[CoapMessage, str, str]], List[Tuple[CoapMessage, str, str]]]:
    """Load all decrypted CoAP messages, returning (requests, responses).

    Each tuple is (message, source_label, direction).
    """
    requests: List[Tuple[CoapMessage, str, str]] = []
    responses: List[Tuple[CoapMessage, str, str]] = []
    with csv_path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            bin_name = row.get("file", "").strip()
            if not bin_name:
                continue
            bin_path = bin_dir / bin_name
            if not bin_path.exists():
                print(f"  warn: frame {row.get('frame')} binary missing: {bin_path}")
                continue
            data = bin_path.read_bytes()
            try:
                msg = parse_coap(data)
            except Exception as exc:
                print(f"  warn: frame {row.get('frame')} CoAP parse failed: {exc}")
                continue
            source = f"frame {row.get('frame', '?')} {bin_name}"
            direction = row.get("direction", "")
            # Heuristic: requests are CON/NON with method codes 0.01-0.04;
            # responses are ACK/RST with response codes 2.xx/4.xx/5.xx.
            if msg.code_class == 0 and msg.code_detail in (1, 2, 3, 4):
                requests.append((msg, source, direction))
            else:
                responses.append((msg, source, direction))
    return requests, responses


def infer_token_paths(
    requests: List[Tuple[CoapMessage, str, str]]
) -> Dict[bytes, str]:
    """Map request token -> URI-Path (including query if present)."""
    mapping: Dict[bytes, str] = {}
    for msg, _, _ in requests:
        if msg.token:
            path = msg.uri_path()
            query = msg.uri_query()
            mapping[msg.token] = f"{path}?{query}" if query else path
    return mapping


def reconstruct_sync_dump(
    csv_path: Path, bin_dir: Path
) -> Tuple[List[ReassembledTransfer], Dict[bytes, str]]:
    """Return (/sync/dump transfers, token->path mapping)."""
    requests, responses = load_all_messages(csv_path, bin_dir)
    token_to_path = infer_token_paths(requests)

    # Collect /sync/dump response blocks, inferring path from request token.
    dump_blocks: List[Tuple[int, int, int, bytes, str]] = []  # (block_num, m, szx, payload, source)
    for msg, source, _ in responses:
        path = token_to_path.get(msg.token, "")
        if path.startswith("/sync/dump"):
            block = msg.block2()
            if block is None:
                # Non-blocked /sync/dump response (e.g. frame 20, block 0 M=0).
                dump_blocks.append((0, 0, 6, msg.payload, source))
            else:
                dump_blocks.append((block.num, block.m, block.szx, msg.payload, source))

    # Sort by frame order (source string begins with "frame N").
    dump_blocks.sort(key=lambda x: int(x[4].split()[1].split()[0]) if x[4].startswith("frame ") else 0)

    # Split into transfers when block number resets to 0.
    transfers: List[ReassembledTransfer] = []
    current: Optional[ReassembledTransfer] = None
    seen_blocks: Optional[set] = None
    for num, m, szx, payload, source in dump_blocks:
        if current is None or num == 0:
            current = ReassembledTransfer(path="/sync/dump")
            transfers.append(current)
            seen_blocks = set()
        if num in seen_blocks:
            # Duplicate block number in a single transfer is unexpected.
            continue
        seen_blocks.add(num)
        current.blocks[num] = BlockRecord(number=num, payload=payload, m=m, szx=szx, source=source)

    return transfers, token_to_path


def main() -> int:
    csv_path = Path(r"G:\Dev\BlueBit\inspire3_decryption_analysis\decrypted_coap_samples.csv")
    bin_dir = csv_path.parent
    if len(sys.argv) > 1:
        csv_path = Path(sys.argv[1])
        bin_dir = csv_path.parent

    if not csv_path.exists():
        print(f"CSV not found: {csv_path}")
        return 1

    sync_dumps, token_to_path = reconstruct_sync_dump(csv_path, bin_dir)

    print(f"Found {len(sync_dumps)} /sync/dump transfer(s)")
    complete = 0
    for i, dump in enumerate(sync_dumps, 1):
        print(f"\nTransfer #{i}: {dump.summary()}")
        if dump.missing_blocks:
            print(f"  Missing blocks: {dump.missing_blocks}")
        if dump.is_complete:
            complete += 1

    print(f"\nComplete transfers: {complete}/{len(sync_dumps)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
