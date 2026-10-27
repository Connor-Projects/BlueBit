"""T3/T4 -- mock firmware resource policy, and transport vs application level.

Key distinction under test: a ``4.01`` is a **CoAP response**, which can only
exist on an already-established DTLS session. It is therefore an *application*
level denial, categorically different from a transport/credential failure (a
fatal alert, no CoAP at all).
"""

from __future__ import annotations

import unittest

from harness.mock_stack import coap
from harness.mock_stack.coap_router import (
    CredentialPresencePolicy,
    MockFirmware,
    OwnershipPolicy,
    SessionIdentityPolicy,
)
from harness.mock_stack.coap import code_name
from harness.models.scenarios import by_key
from harness.models.world_models import transport


class TestFirmwarePolicy(unittest.TestCase):
    def setUp(self):
        self.fw = MockFirmware(SessionIdentityPolicy())

    def status(self, session_kind, path, owned=True):
        return code_name(self.fw.handle(session_kind=session_kind, device_owned=owned,
                                        path=path, method=coap.PUT).status)

    def test_public_resource_allowed_under_bootstrap(self):
        self.assertEqual(self.status("BOOTSTRAP", "/pair/display"), "2.04")

    def test_control_resource_denied_under_bootstrap(self):
        self.assertEqual(self.status("BOOTSTRAP", "/bond/control"), "4.01")
        self.assertEqual(self.status("BOOTSTRAP", "/sync/config"), "4.01")

    def test_control_resource_allowed_under_owner_md(self):
        self.assertEqual(self.status("OWNER_MD", "/bond/control"), "2.04")


class TestTransportVsApplication(unittest.TestCase):
    def test_bond_control_is_application_level_denial(self):
        # The 4.01 arrived as a CoAP message, so the DTLS session existed.
        scen = by_key("OBS_BOND_CONTROL")
        res = transport(scen)
        self.assertTrue(res.established, "session must be established for a CoAP reply")
        self.assertEqual(res.session_kind, "BOOTSTRAP")

        outcome = by_key("OBS_BOND_CONTROL")
        self.assertEqual(scen.observed_transport, "SESSION")
        self.assertEqual(scen.observed_status, "4.01")

    def test_pair_display_shares_the_same_transport(self):
        a = transport(by_key("OBS_PAIR_DISPLAY"))
        b = transport(by_key("OBS_BOND_CONTROL"))
        self.assertEqual(a.session_kind, b.session_kind)  # same BOOTSTRAP session

    def test_transport_failure_explanation_is_contradicted(self):
        # A transport-failure model would predict a fatal alert, not a CoAP 4.01.
        res = transport(by_key("OBS_BOND_CONTROL"))
        self.assertTrue(res.established)
        self.assertIsNone(res.fatal_alert)


class TestPoliciesDiffer(unittest.TestCase):
    def test_credential_and_session_policies_agree(self):
        a = MockFirmware(CredentialPresencePolicy())
        c = MockFirmware(SessionIdentityPolicy())
        for session_kind in ("BOOTSTRAP", "OWNER_MD"):
            for owned in (True, False):
                for path in ("/pair/display", "/bond/control", "/sync/config", "/sync/dump"):
                    ra = a.handle(session_kind=session_kind, device_owned=owned, path=path, method=coap.PUT)
                    rc = c.handle(session_kind=session_kind, device_owned=owned, path=path, method=coap.PUT)
                    self.assertEqual(ra, rc, f"{session_kind}/{owned}/{path}")

    def test_ownership_policy_differs_on_a_fresh_device(self):
        d = MockFirmware(OwnershipPolicy())
        c = MockFirmware(SessionIdentityPolicy())
        fresh_bootstrap_bond = dict(session_kind="BOOTSTRAP", device_owned=False,
                                    path="/bond/control", method=coap.PUT)
        self.assertEqual(code_name(c.handle(**fresh_bootstrap_bond).status), "4.01")
        self.assertEqual(code_name(d.handle(**fresh_bootstrap_bond).status), "2.04")


if __name__ == "__main__":
    unittest.main()
