"""Synthetic and public identities for the Phase 17 offline harness.

PROVENANCE RULES (read before use)
----------------------------------
* ``hello`` and ``BOOTSTRAP`` are **public, hard-coded** values shared by every
  Fitbit device and by BlueBit. They are reproduced from the decoded APK
  (``HelloTlsKeyResolverKt`` / ``BootstrapTlsKeyResolverKt``) and from
  ``BlueBitApp/.../bindings/dtls/``. They are not secrets.
* Every ``MD-*`` key material in this file is **SYNTHETIC and deliberately
  fake**. The harness uses it only to drive identity-negotiation logic.
* ``REAL_MD_ID`` reproduces, as a *non-secret identifier string* already present
  in repository evidence, the device identity observed on the wire
  (``MD-A376B03C-6AF89570``). Its key is **unknown and absent** (``None``) and
  is never fabricated, derived, or claimed anywhere in this harness.

Nothing here reproduces the tracker: these are inputs to a model.
"""

from __future__ import annotations

from typing import Optional

# --- Public, hard-coded values (not secrets) -------------------------------

HELLO_ID: bytes = b"hello"
HELLO_KEY: bytes = bytes(range(0x00, 0x10))  # 00 01 .. 0F

BOOTSTRAP_ID: bytes = b"BOOTSTRAP"
BOOTSTRAP_KEY: bytes = bytes.fromhex("810654E336ADCAB0A03C60F74AA0B6FB")

# --- Clearly-labelled SYNTHETIC MobileData identity -------------------------
# Used only to exercise the negotiation/authorization logic. NOT a real key.

SYNTHETIC_MD_ID: bytes = b"MD-00000000-00000000"
SYNTHETIC_MD_KEY: bytes = bytes([0xA5] * 16)

# A second synthetic identity, used to show that two distinct MD identities are
# independent (negative tests).
SYNTHETIC_MD_ID_2: bytes = b"MD-11111111-11111111"
SYNTHETIC_MD_KEY_2: bytes = bytes([0x5A] * 16)

# --- Real device identifier (identifier only; key NEVER present) ------------

REAL_MD_ID: bytes = b"MD-A376B03C-6AF89570"  # non-secret device identifier
REAL_MD_KEY: Optional[bytes] = None  # unknown; never fabricated

# An OPAQUE stand-in used ONLY to model the *equality* of a shared secret between
# the tracker (client) and the official app (server) in scenarios that require a
# completed MobileData session. It is NOT the real key and carries no information
# about it; it merely lets the model say "both sides hold the same value".
OPAQUE_SHARED_KEY_PLACEHOLDER: bytes = b"\x00OPAQUE-NOT-A-REAL-KEY\x00"


def hexid(identity: bytes) -> str:
    """Return the uppercase hex of an identity, matching captured logs."""
    return identity.hex().upper()
