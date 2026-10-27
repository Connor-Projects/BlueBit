"""S2 -- resource x status table for a BOOTSTRAP vs an owner-authenticated session."""

from __future__ import annotations

from harness.mock_stack import coap
from harness.mock_stack.coap import code_name
from harness.mock_stack.coap_router import MockFirmware, SessionIdentityPolicy


def _row(fw, session_kind, path, method):
    resp = fw.handle(session_kind=session_kind, device_owned=True, path=path, method=method)
    return f"  {session_kind:<10} {path:<16} {code_name(resp.status)}"


def run() -> str:
    fw = MockFirmware(SessionIdentityPolicy())
    lines = ["[SYNTHETIC] S2 -- mock firmware resource policy"]
    for session_kind in ("BOOTSTRAP", "OWNER_MD"):
        for path, method in (
            ("/pair/display", coap.PUT),
            ("/sync/dump", coap.GET),
            ("/sync/response", coap.PUT),
            ("/sync/config", coap.GET),
            ("/bond/control", coap.PUT),
        ):
            lines.append(_row(fw, session_kind, path, method))
    lines.append("  => /pair/display succeeds on both sessions; /bond/control only on an")
    lines.append("     owner-authenticated session. (policy is an assumption, synthetic)")
    return "\n".join(lines)


if __name__ == "__main__":
    print(run())
