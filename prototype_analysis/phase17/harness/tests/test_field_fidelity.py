"""T8 -- field fidelity: the harness decodes the real captured bytes exactly.

Vectors are reproduced verbatim from
``inspire3_decryption_analysis/decrypted_coap_samples.csv``. The harness must not
rewrite or reinterpret them; parsing is delegated to the repository's existing
``offline_poc/coap_parser.py``.
"""

from __future__ import annotations

import csv
import unittest
from pathlib import Path

from harness.mock_stack.coap import parse_coap_hex

CSV = Path(r"G:\Dev\BlueBit\inspire3_decryption_analysis\decrypted_coap_samples.csv")

# frame -> (direction, hex, expected code name, expected uri path)
VECTORS = {
    9: ("phone->tracker", "4403000087aacacfb47061697207646973706c6179d10306ff0801", "0.03 PUT", "/pair/display"),
    12: ("phone->tracker", "4401000187aacad0b473796e6306636f6e666967", "0.01 GET", "/sync/config"),
    13: ("tracker->phone", "6444000087aacacf", "2.04 Changed", ""),
    14: ("tracker->phone", "6481000187aacad0", "4.01 Unauthorized", ""),
    15: ("tracker->phone", "4402000100003c30b26d640433643032ff0a100000186e000040008000000000f17b171090d0e09eabc8c9f20718012000", "0.02 POST", "/md/3d02"),
    16: ("phone->tracker", "6481000100003c30", "4.01 Unauthorized", ""),
}


class TestCapturedVectors(unittest.TestCase):
    def test_vectors_decode_exactly(self):
        for frame, (_direction, hx, code_name, path) in VECTORS.items():
            msg = parse_coap_hex(hx)
            self.assertEqual(msg.code_name, code_name, f"frame {frame} code")
            self.assertEqual(msg.uri_path(), path, f"frame {frame} path")

    def test_4xx_responses_pair_to_requests_by_token(self):
        req = parse_coap_hex(VECTORS[12][1])
        resp = parse_coap_hex(VECTORS[14][1])
        self.assertEqual(req.token_hex, resp.token_hex)
        self.assertEqual(resp.code_detail, 1)  # 4.01

    def test_md_3d02_is_phone_side_unauthorized(self):
        req = parse_coap_hex(VECTORS[15][1])
        resp = parse_coap_hex(VECTORS[16][1])
        self.assertEqual(req.token_hex, resp.token_hex)
        self.assertEqual(VECTORS[15][0], "tracker->phone")
        self.assertEqual(VECTORS[16][0], "phone->tracker")


@unittest.skipUnless(CSV.exists(), f"missing {CSV}")
class TestCapturedCorpus(unittest.TestCase):
    def setUp(self):
        with CSV.open(encoding="utf-8") as fh:
            self.rows = list(csv.DictReader(fh))

    def test_message_count(self):
        self.assertEqual(len(self.rows), 74)

    def test_every_row_parses(self):
        for row in self.rows:
            parse_coap_hex(row["first_128_hex"].strip())

    def test_expected_resource_status_pairs_are_present(self):
        found = set()
        for row in self.rows:
            msg = parse_coap_hex(row["first_128_hex"].strip())
            if msg.uri_path():
                found.add((row["direction"], msg.uri_path()))
        self.assertIn(("phone->tracker", "/pair/display"), found)
        self.assertIn(("phone->tracker", "/sync/config"), found)
        self.assertIn(("phone->tracker", "/sync/dump"), found)
        self.assertIn(("tracker->phone", "/md/3d02"), found)


if __name__ == "__main__":
    unittest.main()
