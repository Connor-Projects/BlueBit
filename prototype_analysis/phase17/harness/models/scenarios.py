"""Scenarios that the competing explanations are asked to predict.

An :class:`Outcome` is what a model predicts will be observable. A
:class:`Scenario` fully specifies the *inputs* (device ownership, which phone
stack is used, what the tracker offers, what the server holds, and the request).

Two classes of scenario are distinguished:

* **observed** scenarios encode facts already captured from the tracker or the
  BlueBit live diagnostic (``source`` is set). These are the anchors every
  explanation must reproduce.
* **discriminating** scenarios are *not yet observed*; they are the experiments
  that would separate the explanations. ``reachability`` records why each is not
  currently permitted.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional, Sequence, Tuple

from harness.mock_stack import coap
from harness.mock_stack.coap import code_name
from harness.synthetic_ids import (
    BOOTSTRAP_ID,
    BOOTSTRAP_KEY,
    OPAQUE_SHARED_KEY_PLACEHOLDER,
    REAL_MD_ID,
    SYNTHETIC_MD_ID,
    SYNTHETIC_MD_KEY,
    SYNTHETIC_MD_KEY_2,
)

# (identity offered by the tracker, key the tracker holds for it)
Offer = Tuple[bytes, Optional[bytes]]
# (identity, key) pairs the *server* (phone) holds in its MobileData store
MdKey = Tuple[bytes, bytes]


@dataclass(frozen=True)
class Outcome:
    """A model's predicted observable."""

    transport: str                  # "SESSION" or "ALERT"
    alert: Optional[int]            # fatal alert description if transport != SESSION
    session_kind: str               # "BOOTSTRAP" | "OWNER_MD" | "NONE"
    status: Optional[Tuple[int, int]]  # CoAP (class, detail) if a response came back
    app_error: Optional[int]        # Airlink error code after the app-side mapping

    def render(self) -> str:
        if self.transport != "SESSION":
            return f"{self.transport} alert={self.alert}"
        return f"{code_name(self.status)} app={self.app_error}"  # type: ignore[arg-type]

    def observable(self) -> tuple:
        """The externally observable outcome (excludes model-internal labels).

        ``session_kind`` is a label a model *infers*; the observable is whether a
        transport formed, any fatal alert, and the CoAP status/app error. Two
        models are only distinguishable if their observables differ.
        """
        return (self.transport, self.alert, self.status, self.app_error)


@dataclass(frozen=True)
class Scenario:
    key: str
    description: str
    device_owned: bool
    server: str                              # "bluebit" | "official"
    offers: Sequence[Offer]
    path: str
    method: int
    server_md_keys: Sequence[MdKey] = ()
    observed_status: Optional[str] = None    # e.g. "4.01" if this was captured
    observed_transport: Optional[str] = None
    source: str = ""                         # where the observation came from
    reachability: str = "model"              # why it is / is not currently runnable

    @property
    def observed(self) -> bool:
        return self.observed_status is not None


# --- shared offer lists -----------------------------------------------------

def bluebit_offers() -> Tuple[Offer, ...]:
    """What the tracker offers when BlueBit is the (server) hub.

    BlueBit has no MobileData resolver, so the first offer cannot be resolved and
    the tracker falls back to the public bootstrap identity.
    """
    return ((REAL_MD_ID, None), (BOOTSTRAP_ID, BOOTSTRAP_KEY))


# --- observed anchors (facts) ----------------------------------------------

OBSERVED: Tuple[Scenario, ...] = (
    Scenario(
        key="OBS_PAIR_DISPLAY",
        description="Captured: phone -> tracker PUT /pair/display under BOOTSTRAP",
        device_owned=True,
        server="bluebit",
        offers=bluebit_offers(),
        path="/pair/display",
        method=coap.PUT,
        observed_status="2.04",
        observed_transport="SESSION",
        source="decrypted_coap_samples.csv frame 9 -> frame 13",
        reachability="captured",
    ),
    Scenario(
        key="OBS_SYNC_CONFIG",
        description="Captured: phone -> tracker GET /sync/config under BOOTSTRAP",
        device_owned=True,
        server="bluebit",
        offers=bluebit_offers(),
        path="/sync/config",
        method=coap.GET,
        observed_status="4.01",
        observed_transport="SESSION",
        source="decrypted_coap_samples.csv frame 12 -> frame 14",
        reachability="captured",
    ),
    Scenario(
        key="OBS_BOND_CONTROL",
        description="Live BlueBit diagnostic: phone -> tracker PUT /bond/control",
        device_owned=True,
        server="bluebit",
        offers=bluebit_offers(),
        path="/bond/control",
        method=coap.PUT,
        observed_status="4.01",
        observed_transport="SESSION",
        source="BlueBit live diagnostic (bond/control), DTLS session established",
        reachability="live-bluebit",
    ),
)


# --- discriminating scenarios (would separate the explanations) -------------

DISCRIMINATING: Tuple[Scenario, ...] = (
    Scenario(
        key="DIS_FRESH_BOOTSTRAP_BOND",
        description="Fresh/unowned device, BOOTSTRAP session, PUT /bond/control",
        device_owned=False,
        server="bluebit",
        offers=((BOOTSTRAP_ID, BOOTSTRAP_KEY),),
        path="/bond/control",
        method=coap.PUT,
        reachability="REQUIRES FACTORY RESET (forbidden)",
    ),
    Scenario(
        key="DIS_OWNED_OFFICIAL_MD_BOND",
        description="Owned device, MobileData-authenticated session, PUT /bond/control",
        device_owned=True,
        server="official",
        offers=((REAL_MD_ID, OPAQUE_SHARED_KEY_PLACEHOLDER), (BOOTSTRAP_ID, BOOTSTRAP_KEY)),
        server_md_keys=((REAL_MD_ID, OPAQUE_SHARED_KEY_PLACEHOLDER),),
        path="/bond/control",
        method=coap.PUT,
        reachability="REQUIRES real key / official app on device (needs approval)",
    ),
    Scenario(
        key="DIS_SYNTH_MD_WRONGKEY_BOND",
        description="Server holds a SYNTHETIC MD key that differs from the tracker's key",
        device_owned=True,
        server="official",
        offers=((SYNTHETIC_MD_ID, SYNTHETIC_MD_KEY), (BOOTSTRAP_ID, BOOTSTRAP_KEY)),
        server_md_keys=((SYNTHETIC_MD_ID, SYNTHETIC_MD_KEY_2),),
        path="/bond/control",
        method=coap.PUT,
        reachability="REQUIRES registering a synthetic resolver on device (E5, needs approval)",
    ),
    Scenario(
        key="DIS_FRESH_BOOTSTRAP_DISPLAY",
        description="Fresh/unowned device, BOOTSTRAP session, PUT /pair/display",
        device_owned=False,
        server="bluebit",
        offers=((BOOTSTRAP_ID, BOOTSTRAP_KEY),),
        path="/pair/display",
        method=coap.PUT,
        reachability="REQUIRES FACTORY RESET (forbidden)",
    ),
)


ALL: Tuple[Scenario, ...] = OBSERVED + DISCRIMINATING


def by_key(key: str) -> Scenario:
    for s in ALL:
        if s.key == key:
            return s
    raise KeyError(key)
