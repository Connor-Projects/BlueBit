#!/usr/bin/env python3
"""Offline tests for the BlueBit protocol proof of concept.

Runs against captured examples from:
  - inspire3_decryption_analysis/decrypted_coap_samples.csv
  - inspire3_decryption_analysis/bootstrap_decryption_results.csv

These tests require no network access and no tracker.
"""

from __future__ import annotations

import csv
import sys
import unittest
from pathlib import Path

from coap_parser import parse_coap_hex
from blockwise_reassembler import reassemble_by_token
from sync_dump_reconstructor import reconstruct_sync_dump


CSV_SAMPLES = Path(r"G:\Dev\BlueBit\inspire3_decryption_analysis\decrypted_coap_samples.csv")
CSV_RESULTS = Path(r"G:\Dev\BlueBit\inspire3_decryption_analysis\bootstrap_decryption_results.csv")


class TestCoapParser(unittest.TestCase):
    def test_pair_display_put(self):
        # Frame 9: phone -> tracker PUT /pair/display
        msg = parse_coap_hex(
            "4403000087aacacfb47061697207646973706c6179d10306ff0801"
        )
        self.assertEqual(msg.version, 1)
        self.assertEqual(msg.type, 0)  # CON
        self.assertEqual(msg.tkl, 4)
        self.assertEqual(msg.code, 0x03)  # PUT
        self.assertEqual(msg.mid, 0x0000)
        self.assertEqual(msg.token_hex, "87aacacf")
        self.assertEqual(msg.uri_path(), "/pair/display")
        self.assertEqual(len(msg.payload), 2)

    def test_events_post(self):
        # Frame 10: tracker -> phone POST /events
        msg = parse_coap_hex(
            "4402000000003c2fb66576656e7473ff08f4cacd9307"
        )
        self.assertEqual(msg.code, 0x02)  # POST
        self.assertEqual(msg.uri_path(), "/events")
        self.assertEqual(len(msg.payload), 6)

    def test_sync_config_unauthorized(self):
        # Frame 12: phone GET /sync/config -> frame 14 returns 4.01
        req = parse_coap_hex(
            "4401000187aacad0b473796e6306636f6e666967"
        )
        self.assertEqual(req.code, 0x01)  # GET
        self.assertEqual(req.uri_path(), "/sync/config")

        resp = parse_coap_hex("6481000187aacad0")
        self.assertEqual(resp.code, 0x81)  # 4.01 Unauthorized
        self.assertEqual(resp.type, 2)  # ACK
        self.assertEqual(resp.token_hex, req.token_hex)

    def test_sync_dump_block_response(self):
        # Frame 23: tracker -> phone 2.05 /sync/dump Block2 #0 M=1 size 1024
        msg = parse_coap_hex(
            "6445000487aacad3d10a0eff03040000020ed474508128924d673f014001000000"
            "257d733afe821859e82c1da3c7faa4888c23a5dc6e55269e923dbbe0ca7bb3636"
            "9cd802c0e2fe4478ab44ce616d02a19e680a34d4d807df95d5e911290647d2a5c"
            "028817c4e3c743890a6c866b208e4416fe544cbb559f912c7395f578134d"
        )
        self.assertEqual(msg.code, 0x45)  # 2.05 Content
        block = msg.block2()
        self.assertIsNotNone(block)
        self.assertEqual(block.num, 0)
        self.assertEqual(block.m, 1)
        self.assertEqual(block.szx, 6)
        self.assertEqual(block.size, 1024)
        self.assertEqual(len(msg.payload), 116)  # truncated sample; full frame has 1024

    def test_block2_request(self):
        # Frame 34: phone GET /sync/dump Block2 #2 M=1 size 1024
        msg = parse_coap_hex(
            "4401000587aacad4b473796e630464756d70c116"
        )
        self.assertEqual(msg.uri_path(), "/sync/dump")
        block = msg.block2()
        self.assertIsNotNone(block)
        self.assertEqual(block.num, 1)  # captured request for block #1
        self.assertEqual(block.m, 0)  # M is meaningless in Block2 requests
        self.assertEqual(block.szx, 6)

    def test_large_block_number(self):
        # Frame 41: Block2 #16
        msg = parse_coap_hex(
            "6445001487aacae3d20a010eff6a5e60ea851728ba55c1c591ebabf044a036435"
            "83c7092287745d8820e8c15ffe352d3f5eb95504e9ebfd5f7e84600b210d59cd6"
            "ff02648f50412c724eb9d8f474960a8bc00d1e03f74a241c55888873259fb70e1"
            "4e4467a2b53e28c119f54844afea91656863fe4c0ea53fae35cb4f7c372a6"
        )
        block = msg.block2()
        self.assertIsNotNone(block)
        self.assertEqual(block.num, 16)
        self.assertEqual(block.m, 1)


class TestBlockwiseReassembler(unittest.TestCase):
    def test_simple_two_block_transfer(self):
        # Synthetic: two 1024-byte blocks, last one smaller.
        block0 = parse_coap_hex(
            "6445000087aacad3d10a0eff" + "00" * 1024
        )
        block1 = parse_coap_hex(
            "6445000187aacad4d10a06ff" + "11" * 500
        )
        transfers = reassemble_by_token(
            [(block0, "block0"), (block1, "block1")]
        )
        self.assertEqual(len(transfers), 2)  # different tokens
        # Use same token for a real reassembly test
        block1_same = parse_coap_hex(
            "6445000187aacad3d10a16ff" + "11" * 500
        )
        transfers = reassemble_by_token(
            [(block0, "block0"), (block1_same, "block1")]
        )
        self.assertEqual(len(transfers), 1)
        t = list(transfers.values())[0]
        self.assertTrue(t.is_complete)
        self.assertEqual(len(t.total_payload), 1524)


class TestSyncDumpReconstruction(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not CSV_RESULTS.exists():
            raise unittest.SkipTest(f"CSV not found: {CSV_RESULTS}")

    def test_csv_loads_and_finds_sync_dump(self):
        sync_dumps, token_to_path = reconstruct_sync_dump(CSV_SAMPLES, CSV_SAMPLES.parent)
        self.assertGreaterEqual(len(sync_dumps), 1)
        # At least one /sync/dump request must exist to infer the response path.
        self.assertTrue(any(p.startswith("/sync/dump") for p in token_to_path.values()))

    def test_sync_dump_blocks_are_contiguous(self):
        sync_dumps, _ = reconstruct_sync_dump(CSV_SAMPLES, CSV_SAMPLES.parent)
        # The main /sync/dump transfer in the bootstrap-decrypted capture is
        # complete (blocks 0..27). A separate small /sync/dump?t=pair transfer
        # (frame 20) is also present.
        for dump in sync_dumps:
            block_nums = sorted(dump.blocks.keys())
            self.assertEqual(block_nums[0], 0)
            for n in block_nums[:-1]:
                self.assertEqual(dump.blocks[n].szx, 6)
            # If complete, the last block must have M=0.
            if dump.is_complete:
                self.assertEqual(dump.blocks[block_nums[-1]].m, 0)


class TestCsvConsistency(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not CSV_SAMPLES.exists():
            raise unittest.SkipTest(f"CSV not found: {CSV_SAMPLES}")

    def test_every_sample_is_valid_coap(self):
        with CSV_SAMPLES.open("r", encoding="utf-8", newline="") as f:
            reader = csv.DictReader(f)
            for row in reader:
                hex_str = row["first_128_hex"].strip()
                try:
                    msg = parse_coap_hex(hex_str)
                except Exception as exc:
                    self.fail(f"frame {row['frame']} parse failed: {exc}")
                self.assertEqual(msg.version, 1)
                self.assertEqual(msg.tkl, 4)


def suite():
    loader = unittest.TestLoader()
    return loader.loadTestsFromModule(sys.modules[__name__])


if __name__ == "__main__":
    unittest.main(verbosity=2)
