"""Mock firmware: a CoAP resource -> authorization table.

This encodes an *assumed* device policy used to drive scenarios. It is NOT the
tracker firmware and does not prove the tracker's real behaviour. Two policies
are provided so the harness can compare the competing explanations:

  * [SessionIdentityPolicy]  -- "resource policy keyed on session identity" (C)
  * [OwnershipPolicy]        -- "resource policy keyed on device ownership" (D)
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Optional, Tuple

from . import coap
from .coap import ExtendedError, Response

BOOTSTRAP = "BOOTSTRAP"
OWNER_MD = "OWNER_MD"

# Non-control resources whose status was observed in the bootstrap capture.
_PUBLIC_RESOURCES: Dict[str, Tuple[int, int]] = {
    "/pair/display": coap.RESPONSE_CHANGED,   # 2.04
    "/sync/dump": coap.RESPONSE_CONTENT,      # 2.05
    "/sync/response": coap.RESPONSE_CHANGED,  # 2.04
}

# Resources gated behind owner trust in policy (C).
_CONTROL_RESOURCES = ("/bond/control", "/sync/config")


class FirmwarePolicy:
    name = "FirmwarePolicy"

    def decision(self, *, session_kind: str, device_owned: bool, path: str,
                 method: int) -> Response:
        raise NotImplementedError

    def _public(self, path: str) -> Optional[Response]:
        if path in _PUBLIC_RESOURCES:
            return Response(_PUBLIC_RESOURCES[path])
        if path == "/events":
            return Response(coap.RESPONSE_CHANGED)
        return None


class SessionIdentityPolicy(FirmwarePolicy):
    """(C) control resources require an owner-authenticated (MobileData) session."""

    name = "SessionIdentityPolicy(C)"

    def decision(self, *, session_kind: str, device_owned: bool, path: str,
                 method: int) -> Response:
        public = self._public(path)
        if public is not None:
            return public
        if path in _CONTROL_RESOURCES:
            if session_kind == OWNER_MD:
                return Response(coap.RESPONSE_CHANGED)
            return Response(coap.RESPONSE_UNAUTHORIZED)
        return Response((4, 4))  # 4.04 Not Found


class OwnershipPolicy(FirmwarePolicy):
    """(D) control resources are refused iff the device is already owned."""

    name = "OwnershipPolicy(D)"

    def decision(self, *, session_kind: str, device_owned: bool, path: str,
                 method: int) -> Response:
        public = self._public(path)
        if public is not None:
            return public
        if path in _CONTROL_RESOURCES:
            if device_owned:
                return Response(coap.RESPONSE_UNAUTHORIZED)
            return Response(coap.RESPONSE_CHANGED)
        return Response((4, 4))


@dataclass
class MockFirmware:
    """A CoAP endpoint whose behaviour is a [FirmwarePolicy]."""

    policy: FirmwarePolicy = SessionIdentityPolicy()

    def handle(self, *, session_kind: str, device_owned: bool, path: str,
               method: int) -> Response:
        return self.policy.decision(session_kind=session_kind,
                                    device_owned=device_owned,
                                    path=path, method=method)


class CredentialPresencePolicy(FirmwarePolicy):
    """(A) control resources require the *server* to possess an owner credential.

    Written as a separate primitive from [SessionIdentityPolicy] so the
    operational equivalence of explanations A and C is *derived* by the tests
    rather than assumed. On the wire, "the server resolved an owner credential"
    and "the session is owner-authenticated" are the same observable, so the two
    policies produce identical truth tables.
    """

    name = "CredentialPresencePolicy(A)"

    def decision(self, *, session_kind: str, device_owned: bool, path: str,
                 method: int) -> Response:
        public = self._public(path)
        if public is not None:
            return public
        if path in _CONTROL_RESOURCES:
            server_holds_owner_credential = (session_kind == OWNER_MD)
            if server_holds_owner_credential:
                return Response(coap.RESPONSE_CHANGED)
            return Response(coap.RESPONSE_UNAUTHORIZED)
        return Response((4, 4))
