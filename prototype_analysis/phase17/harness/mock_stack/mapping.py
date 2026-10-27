"""App-side CoAP response -> Airlink error mapping (documented-rule mirror).

Mirrors ``Decoded/Fitbit-base/sources/com/fitbit/goldengate/CoapResponseUtils.java``
lines 174-186 (and the ``getIntCode`` helper at lines 232-235):

    getIntCode(rc)      = rc.class * 100 + rc.detail
    getResponseCode:
        if intCode == 503 -> 4353 (RF_ERR_STREAM_SYSTEM_NOT_READY)
        if intCode == 401 -> 5378 (RF_ERR_KEY_EXPIRED)      # unconditional,
                                                            # ExtendedError ignored
        if extendedError is None -> 0
        else -> extendedError.code

This is a faithful *re-encoding of the documented rule*; it is not the native
code (the GoldenGate bindings are not on the JVM/Python classpath).
"""

from __future__ import annotations

from typing import Optional, Tuple

from .coap import ExtendedError

# AirlinkErrorCode values (Decoded/.../fbcomms/ota/AirlinkErrorCode.java).
RF_ERR_STREAM_SYSTEM_NOT_READY = 4353
RF_ERR_KEY_EXPIRED = 5378

INT_CODE_SERVICE_UNAVAILABLE = 503
INT_CODE_UNAUTHORIZED = 401


def int_code(response_code: Tuple[int, int]) -> int:
    cls, detail = response_code
    return cls * 100 + detail


def app_error_code(response_code: Tuple[int, int],
                   extended_error: Optional[ExtendedError] = None) -> int:
    """Return the Airlink/app error code the Android layer would produce."""
    ic = int_code(response_code)
    if ic == INT_CODE_SERVICE_UNAVAILABLE:
        return RF_ERR_STREAM_SYSTEM_NOT_READY
    if ic == INT_CODE_UNAUTHORIZED:
        return RF_ERR_KEY_EXPIRED
    if extended_error is None:
        return 0
    return extended_error.code
