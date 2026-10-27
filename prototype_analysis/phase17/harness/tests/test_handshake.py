"""T1 -- DTLS PSK identity negotiation model.

Reproduces the observed BlueBit shape: the tracker offers ``MD-<device>`` first,
the server cannot resolve it and sends fatal ``unknown_psk_identity`` (115), the
tracker then offers ``BOOTSTRAP`` and the session forms under the public key.
"""

from __future__ import annotations

import unittest

from harness.mock_stack.dtls_psk import (
    ALERT_HANDSHAKE_FAILURE,
    ALERT_UNKNOWN_PSK_IDENTITY,
    run_psk_handshake,
)
from harness.mock_stack.resolver import bluebit_chain, official_chain
from harness.synthetic_ids import (
    BOOTSTRAP_ID,
    BOOTSTRAP_KEY,
    OPAQUE_SHARED_KEY_PLACEHOLDER,
    REAL_MD_ID,
    SYNTHETIC_MD_ID,
    SYNTHETIC_MD_KEY,
    SYNTHETIC_MD_KEY_2,
)


class TestHandshakeModel(unittest.TestCase):
    def test_bluebit_falls_back_to_bootstrap(self):
        res = run_psk_handshake(
            bluebit_chain(),
            ((REAL_MD_ID, None), (BOOTSTRAP_ID, BOOTSTRAP_KEY)),
        )
        self.assertTrue(res.established)
        self.assertEqual(res.session_identity, BOOTSTRAP_ID)
        self.assertEqual(res.session_kind, "BOOTSTRAP")
        self.assertEqual(res.requested_ids, [REAL_MD_ID, BOOTSTRAP_ID])
        # the fallback was forced by a fatal alert on the first identity
        alerts = [e for e in res.events if e.kind.startswith("Alert")]
        self.assertTrue(alerts)
        self.assertTrue(any("unknown_psk_identity" in e.detail for e in alerts))

    def test_first_identity_is_rejected_with_115(self):
        res = run_psk_handshake(bluebit_chain(), ((REAL_MD_ID, None),))
        self.assertFalse(res.established)
        self.assertEqual(res.fatal_alert, ALERT_UNKNOWN_PSK_IDENTITY)
        self.assertEqual(res.session_kind, "NONE")

    def test_official_valid_md_session(self):
        res = run_psk_handshake(
            official_chain({SYNTHETIC_MD_ID: SYNTHETIC_MD_KEY}),
            ((SYNTHETIC_MD_ID, SYNTHETIC_MD_KEY), (BOOTSTRAP_ID, BOOTSTRAP_KEY)),
        )
        self.assertTrue(res.established)
        self.assertEqual(res.session_kind, "OWNER_MD")
        self.assertEqual(res.requested_ids, [SYNTHETIC_MD_ID])

    def test_md_key_mismatch_fails_at_finished(self):
        # server holds a different key for the offered identity
        res = run_psk_handshake(
            official_chain({SYNTHETIC_MD_ID: SYNTHETIC_MD_KEY_2}),
            ((SYNTHETIC_MD_ID, SYNTHETIC_MD_KEY), (BOOTSTRAP_ID, BOOTSTRAP_KEY)),
        )
        self.assertTrue(res.established)  # fell back to BOOTSTRAP
        self.assertEqual(res.session_kind, "BOOTSTRAP")
        self.assertEqual(res.requested_ids, [SYNTHETIC_MD_ID, BOOTSTRAP_ID])

    def test_md_key_mismatch_alone_is_fatal(self):
        res = run_psk_handshake(
            official_chain({SYNTHETIC_MD_ID: SYNTHETIC_MD_KEY_2}),
            ((SYNTHETIC_MD_ID, SYNTHETIC_MD_KEY),),
        )
        self.assertFalse(res.established)
        self.assertEqual(res.fatal_alert, ALERT_HANDSHAKE_FAILURE)

    def test_opaque_placeholder_models_real_key_equality_only(self):
        # The placeholder stands in for "both sides hold the same value".
        res = run_psk_handshake(
            official_chain({REAL_MD_ID: OPAQUE_SHARED_KEY_PLACEHOLDER}),
            ((REAL_MD_ID, OPAQUE_SHARED_KEY_PLACEHOLDER), (BOOTSTRAP_ID, BOOTSTRAP_KEY)),
        )
        self.assertTrue(res.established)
        self.assertEqual(res.session_kind, "OWNER_MD")


if __name__ == "__main__":
    unittest.main()
