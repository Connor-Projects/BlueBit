"""DTLS PSK identity negotiation *model* (not real cryptography).

Models the observed shape only:

    client (GG node / tracker) offers a PSK identity -> server (hub / phone)
    walks its resolver chain -> on a miss the server sends a fatal alert
    ``unknown_psk_identity`` (115) and the client retries the next identity;
    on a hit, if both ends hold the same key, a session is established.

No DTLS record, MAC, or cryptographic operation is performed. This is a model.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional, Sequence, Tuple

from .resolver import ResolverChain

# DTLS/TLS alert descriptions used by the model.
ALERT_BAD_RECORD_MAC = 20
ALERT_HANDSHAKE_FAILURE = 40
ALERT_UNKNOWN_PSK_IDENTITY = 115

TRACKER = "tracker->phone"
PHONE = "phone->tracker"


@dataclass(frozen=True)
class HandshakeEvent:
    direction: str
    kind: str
    detail: str = ""

    def render(self) -> str:
        return f"{self.direction:<14} {self.kind:<18} {self.detail}".rstrip()


@dataclass
class HandshakeResult:
    established: bool
    session_identity: Optional[bytes]
    events: List[HandshakeEvent] = field(default_factory=list)
    fatal_alert: Optional[int] = None
    requested_ids: List[bytes] = field(default_factory=list)

    @property
    def session_kind(self) -> str:
        """'BOOTSTRAP' when established under the public bootstrap key, else 'OWNER_MD'."""
        if not self.established or self.session_identity is None:
            return "NONE"
        return "BOOTSTRAP" if self.session_identity == b"BOOTSTRAP" else "OWNER_MD"


def run_psk_handshake(
    server_chain: ResolverChain,
    offered: Sequence[Tuple[bytes, Optional[bytes]]],
    *,
    node_key: str = "D8:9E:C1:BA:3D:E4",
) -> HandshakeResult:
    """Simulate the tracker offering identities in order until one settles.

    ``offered`` is a sequence of ``(identity, client_key)``. ``client_key`` may be
    ``None`` (the tracker has the identity but the model does not use a key).
    """
    events: List[HandshakeEvent] = []
    requested: List[bytes] = []
    last_alert: Optional[int] = None

    for identity, client_key in offered:
        requested.append(identity)
        events.append(HandshakeEvent(TRACKER, "ClientHello", "PSK-AES128-CCM offered"))
        events.append(HandshakeEvent(PHONE, "ServerHello+Done", f"nodeKey={node_key}"))
        events.append(HandshakeEvent(TRACKER, "ClientKeyExchange", f"identity={identity!r}"))

        server_key = server_chain.resolve(identity)
        if server_key is None:
            last_alert = ALERT_UNKNOWN_PSK_IDENTITY
            events.append(HandshakeEvent(PHONE, "Alert(fatal)",
                                         f"desc={ALERT_UNKNOWN_PSK_IDENTITY} unknown_psk_identity"))
            continue

        # Server resolved the identity; the Finished MAC now requires both ends
        # to hold the same secret.
        if client_key is None or server_key != client_key:
            last_alert = ALERT_HANDSHAKE_FAILURE
            events.append(HandshakeEvent(PHONE, "Alert(fatal)",
                                         f"desc={ALERT_HANDSHAKE_FAILURE} decrypt/handshake error"))
            continue

        events.append(HandshakeEvent(PHONE, "ChangeCipherSpec", ""))
        events.append(HandshakeEvent(TRACKER, "ChangeCipherSpec", ""))
        events.append(HandshakeEvent(TRACKER, "Finished", "MAC ok"))
        events.append(HandshakeEvent(PHONE, "Finished", "MAC ok"))
        return HandshakeResult(True, identity, events, None, requested)

    return HandshakeResult(False, None, events, last_alert, requested)
