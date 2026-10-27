"""S1 -- BlueBit bootstrap fallback: why every session ends up under BOOTSTRAP."""

from __future__ import annotations

from harness.mock_stack.dtls_psk import run_psk_handshake
from harness.mock_stack.resolver import bluebit_chain
from harness.models.scenarios import bluebit_offers


def run() -> str:
    lines = ["[SYNTHETIC] S1 -- resolver chain + PSK identity negotiation"]
    chain = bluebit_chain()
    lines.append(f"  server chain        : {' -> '.join(chain.resolver_names)}")
    res = run_psk_handshake(chain, bluebit_offers())
    lines.append(f"  identities offered  : {[i for i, _ in bluebit_offers()]!r}")
    lines.append(f"  requested ids       : {res.requested_ids!r}")
    lines.append(f"  established         : {res.established}")
    lines.append(f"  session kind        : {res.session_kind}")
    lines.append("  trace:")
    for e in res.events:
        lines.append(f"    {e.render()}")
    lines.append("  => BlueBit has no MobileData resolver, so the owner identity is")
    lines.append("     rejected and the session is BOOTSTRAP-only. (synthetic)")
    return "\n".join(lines)


if __name__ == "__main__":
    print(run())
