"""S3 -- what a *synthetic* MobileData session looks like (correct key).

This is the shape of the discriminating experiment "owned device + an
owner-authenticated session + PUT /bond/control". It uses a synthetic key; it
does NOT use or reveal the tracker's real key.
"""

from __future__ import annotations

from harness.mock_stack import coap
from harness.mock_stack.coap import code_name
from harness.mock_stack.coap_router import CredentialPresencePolicy, MockFirmware
from harness.mock_stack.dtls_psk import run_psk_handshake
from harness.mock_stack.resolver import official_chain
from harness.synthetic_ids import BOOTSTRAP_ID, BOOTSTRAP_KEY, SYNTHETIC_MD_ID, SYNTHETIC_MD_KEY


def run() -> str:
    chain = official_chain({SYNTHETIC_MD_ID: SYNTHETIC_MD_KEY})
    res = run_psk_handshake(chain, ((SYNTHETIC_MD_ID, SYNTHETIC_MD_KEY),))
    lines = [
        "[SYNTHETIC] S3 -- synthetic MobileData session (fake key)",
        f"  established : {res.established}",
        f"  session kind: {res.session_kind}",
    ]
    fw = MockFirmware(CredentialPresencePolicy())
    for path in ("/pair/display", "/bond/control", "/sync/config"):
        resp = fw.handle(session_kind=res.session_kind, device_owned=True, path=path, method=coap.PUT)
        lines.append(f"  {path:<16} {code_name(resp.status)}")
    lines.append("  => Under an owner-authenticated session the model predicts 2.04 for")
    lines.append("     /bond/control. This is a MODEL prediction, not a device result.")
    return "\n".join(lines)


if __name__ == "__main__":
    print(run())
