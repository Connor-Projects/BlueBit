"""T7 -- gattlink framing model and the one-outstanding-write serializer."""

from __future__ import annotations

import unittest

from harness.mock_stack.gattlink_framing import (
    MTU_ADVERT_PAYLOAD,
    frame_control_empty,
    frame_control_mtu,
    frame_data,
    ipv4_total_length,
    is_control_prefix,
    reassemble,
)
from harness.mock_stack.tx_serializer import SerialTxSink


def _fake_ipv4(payload: bytes) -> bytes:
    total = 20 + len(payload)
    header = bytearray(20)
    header[0] = 0x45
    header[2:4] = total.to_bytes(2, "big")
    return bytes(header) + payload


class TestFraming(unittest.TestCase):
    def test_prefix_classes(self):
        self.assertFalse(is_control_prefix(0x00))
        self.assertFalse(is_control_prefix(0x7F))
        self.assertTrue(is_control_prefix(0x80))
        self.assertTrue(is_control_prefix(0x81))

    def test_observed_control_pdus(self):
        self.assertEqual(frame_control_mtu(), bytes.fromhex("8100000808"))
        self.assertEqual(MTU_ADVERT_PAYLOAD, bytes.fromhex("00000808"))
        self.assertEqual(frame_control_empty(), bytes.fromhex("80"))

    def test_data_pdu_round_trip(self):
        ip = _fake_ipv4(b"hello")
        pdu = frame_data(ip, prefix=0x00)
        self.assertEqual(pdu[0], 0x00)
        self.assertEqual(pdu[1:], ip)
        self.assertEqual(reassemble([pdu]), [ip])

    def test_reassembly_splits_on_ipv4_total_length(self):
        a = _fake_ipv4(b"aaaa")
        b = _fake_ipv4(b"bbbbbb")
        # a single IPv4 packet split across several data PDUs
        self.assertEqual(reassemble([frame_data(a[:3]), frame_data(a[3:5]), frame_data(a[5:])]), [a])
        # two packets, one split across PDUs, in one direction
        self.assertEqual(reassemble([frame_data(a[:3]), frame_data(a[3:]), frame_data(b)]), [a, b])

    def test_control_pdus_are_ignored(self):
        ip = _fake_ipv4(b"x")
        pdus = [frame_control_empty(), frame_data(ip), frame_control_mtu()]
        self.assertEqual(reassemble(pdus), [ip])

    def test_total_length_helper(self):
        ip = _fake_ipv4(b"12345")
        self.assertEqual(ipv4_total_length(ip), 25)


class TestSerializer(unittest.TestCase):
    def test_one_outstanding_at_a_time(self):
        sink = SerialTxSink(lambda f: None)
        sink.enqueue(b"a")
        sink.enqueue(b"b")
        sink.enqueue(b"c")
        self.assertEqual(sink.submitted, [b"a"])
        sink.complete()
        self.assertEqual(sink.submitted, [b"a", b"b"])
        sink.complete()
        self.assertEqual(sink.submitted, [b"a", b"b", b"c"])

    def test_order_preserved(self):
        seen = []
        sink = SerialTxSink(lambda f: seen.append(f))
        for f in (b"1", b"2", b"3"):
            sink.enqueue(f)
        sink.complete()
        sink.complete()
        self.assertEqual(seen, [b"1", b"2", b"3"])

    def test_refused_submission_is_reported(self):
        def refuse(_):
            raise RuntimeError("platform refused")
        sink = SerialTxSink(refuse)
        sink.enqueue(b"a")
        self.assertEqual(sink.submitted, [])
        self.assertEqual(len(sink.failures), 1)
        self.assertIsNone(sink.pending)

    def test_failed_completion_does_not_masquerade(self):
        sink = SerialTxSink(lambda f: None)
        sink.enqueue(b"a")
        sink.complete(ok=False)
        self.assertEqual(len(sink.failures), 1)
        self.assertEqual(sink.failures[0][0], b"a")


if __name__ == "__main__":
    unittest.main()
