"""The competing explanations, expressed as executable models.

Each model is a claim about *why* ``PUT bond/control`` returns ``4.01`` while
``PUT pair/display`` returns ``2.04``. Making them executable forces them to
state their predictions precisely, so we can check whether they differ.

Transport is shared: it is fixed by the *scenario* (which phone stack is used and
what the server holds), not by the model. A model only varies the firmware policy
that judges the request once a session exists. The single exception is
[NaiveInterchangeModel], which is deliberately wrong and is used as a negative
control.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Sequence, Tuple

from harness.mock_stack.coap_router import (
    FirmwarePolicy,
    OwnershipPolicy,
    CredentialPresencePolicy,
    SessionIdentityPolicy,
    MockFirmware,
)
from harness.mock_stack.dtls_psk import ALERT_UNKNOWN_PSK_IDENTITY, run_psk_handshake
from harness.mock_stack.mapping import app_error_code
from harness.mock_stack.resolver import bluebit_chain, official_chain

from .scenarios import Outcome, Scenario


def transport(scenario: Scenario):
    """Run the (model-independent) DTLS PSK identity negotiation."""
    if scenario.server == "bluebit":
        chain = bluebit_chain()
    else:
        chain = official_chain(dict(scenario.server_md_keys))
    return run_psk_handshake(chain, scenario.offers)


@dataclass
class WorldModel:
    key: str
    name: str
    description: str
    firmware: FirmwarePolicy
    ruled_out: bool = False
    ruled_out_reason: str = ""

    def predict(self, scenario: Scenario) -> Outcome:
        res = transport(scenario)
        if not res.established:
            return Outcome("ALERT", res.fatal_alert, "NONE", None, None)
        resp = self.firmware.decision(
            session_kind=res.session_kind,
            device_owned=scenario.device_owned,
            path=scenario.path,
            method=scenario.method,
        )
        return Outcome(
            "SESSION",
            None,
            res.session_kind,
            resp.status,
            app_error_code(resp.status, resp.extended_error),
        )


class NaiveInterchangeModel(WorldModel):
    """NEGATIVE CONTROL: treats the three explanations as interchangeable.

    It assumes that merely naming an ``MD-*`` identity is enough to authorise a
    control resource, *even though the handshake never completed*. It exists so
    the tests can show it is falsified by the transport evidence.
    """

    def predict(self, scenario: Scenario) -> Outcome:
        res = transport(scenario)
        identity_hint = scenario.offers[0][0] if scenario.offers else b""
        session_kind = "OWNER_MD" if identity_hint.startswith(b"MD-") else res.session_kind
        resp = self.firmware.decision(
            session_kind=session_kind,
            device_owned=scenario.device_owned,
            path=scenario.path,
            method=scenario.method,
        )
        # The naive model reports an application response regardless of whether
        # a transport actually formed.
        return Outcome(
            "SESSION",
            None,
            session_kind,
            resp.status,
            app_error_code(resp.status, resp.extended_error),
        )


# --- the four substantive explanations + two controls -----------------------

MODEL_A_CREDENTIAL = WorldModel(
    key="A",
    name="Missing owner credential",
    description=(
        "BlueBit cannot resolve the tracker's MobileData identity, so no "
        "owner-authenticated session is possible; the tracker withholds control "
        "resources from a non-owner session."
    ),
    firmware=CredentialPresencePolicy(),
)

MODEL_C_RESOURCE_POLICY = WorldModel(
    key="C",
    name="Resource policy keyed on session identity",
    description=(
        "The tracker applies a per-session resource ACL: control resources "
        "require a MobileData (owner) session; public resources do not."
    ),
    firmware=SessionIdentityPolicy(),
)

MODEL_D_OWNERSHIP = WorldModel(
    key="D",
    name="Ownership / pairing-state gating",
    description=(
        "The tracker refuses control resources on an already-owned device "
        "regardless of session type; a fresh device would accept them."
    ),
    firmware=OwnershipPolicy(),
)

MODEL_E_EXPIRY = WorldModel(
    key="E",
    name="Key expiry",
    description="The tracker refuses control resources because a key has expired.",
    firmware=OwnershipPolicy(),  # same observables as D on the current scenario set
    ruled_out=True,
    ruled_out_reason=(
        "Source: CoapResponseUtils.java:174-186 maps 4.01 -> RF_ERR_KEY_EXPIRED "
        "unconditionally, ignoring any device ExtendedError; the app-side code is "
        "not evidence of a real expiry."
    ),
)

MODEL_NAIVE = NaiveInterchangeModel(
    key="N",
    name="Naive interchange (negative control)",
    description=(
        "Any MD-* identity is treated as sufficient authorization for control "
        "resources. Intended to be falsified."
    ),
    firmware=SessionIdentityPolicy(),
)

MODELS: Tuple[WorldModel, ...] = (
    MODEL_A_CREDENTIAL,
    MODEL_C_RESOURCE_POLICY,
    MODEL_D_OWNERSHIP,
    MODEL_E_EXPIRY,
    MODEL_NAIVE,
)
