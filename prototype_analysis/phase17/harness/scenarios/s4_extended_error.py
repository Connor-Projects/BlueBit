"""S4 -- app-side ExtendedError masking / passthrough."""

from __future__ import annotations

from harness.mock_stack.coap import (
    ExtendedError,
    RESPONSE_FORBIDDEN,
    RESPONSE_SERVICE_UNAVAILABLE,
    RESPONSE_UNAUTHORIZED,
)
from harness.mock_stack.mapping import app_error_code, int_code


def run() -> str:
    lines = ["[SYNTHETIC] S4 -- CoAP -> Airlink error mapping (documented-rule mirror)"]
    cases = (
        ("4.01 + ExtendedError(0x1234)", RESPONSE_UNAUTHORIZED, ExtendedError(0x1234)),
        ("4.01 + no ExtendedError", RESPONSE_UNAUTHORIZED, None),
        ("5.03 + ExtendedError(0x1234)", RESPONSE_SERVICE_UNAVAILABLE, ExtendedError(0x1234)),
        ("4.03 + ExtendedError(0x1234)", RESPONSE_FORBIDDEN, ExtendedError(0x1234)),
        ("4.03 + no ExtendedError", RESPONSE_FORBIDDEN, None),
    )
    for label, rc, ee in cases:
        lines.append(f"  {label:<30} intCode={int_code(rc):<4} -> app={app_error_code(rc, ee)}")
    lines.append("  => 4.01 is *always* mapped to 5378 (RF_ERR_KEY_EXPIRED); the mapping")
    lines.append("     ignores the device's ExtendedError, so it is not evidence of a")
    lines.append("     genuine key expiry. (mirror of CoapResponseUtils.java)")
    return "\n".join(lines)


if __name__ == "__main__":
    print(run())
