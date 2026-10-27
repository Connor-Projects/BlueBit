"""DTLS PSK resolver chain *model* (the server/hub side).

Mirrors the semantics of ``BlueBitApp/.../bindings/dtls/``:

    TlsKeyResolver.resolve(keyId) walks ``Hello -> Bootstrap -> ?next`` and
    returns the first non-null key, recording each requested id.

The default BlueBit chain is ``Hello -> Bootstrap`` (NO MobileData resolver).
The official chain is ``Hello -> Bootstrap -> MobileData`` where the MobileData
resolver maps ``MD-*`` identities to a per-device key store.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Tuple

from ..synthetic_ids import BOOTSTRAP_ID, BOOTSTRAP_KEY, HELLO_ID, HELLO_KEY


class Resolver:
    name = "Resolver"

    def resolve_key(self, key_id: bytes) -> Optional[bytes]:
        raise NotImplementedError


class HelloResolver(Resolver):
    name = "HelloTlsKeyResolver"

    def resolve_key(self, key_id: bytes) -> Optional[bytes]:
        return HELLO_KEY if key_id == HELLO_ID else None


class BootstrapResolver(Resolver):
    name = "BootstrapTlsKeyResolver"

    def resolve_key(self, key_id: bytes) -> Optional[bytes]:
        return BOOTSTRAP_KEY if key_id == BOOTSTRAP_ID else None


class MobileDataResolver(Resolver):
    """Per-device ``MD-*`` key store (present only in the official app)."""

    name = "MobileDataTlsKeyResolver"

    def __init__(self, keys: Optional[Dict[bytes, bytes]] = None) -> None:
        self._keys: Dict[bytes, bytes] = dict(keys or {})

    def add(self, key_id: bytes, key: bytes) -> None:
        self._keys[key_id] = key

    def resolve_key(self, key_id: bytes) -> Optional[bytes]:
        return self._keys.get(key_id)


@dataclass
class ResolveCall:
    resolver: str
    key_id: bytes
    found: bool


class ResolverChain:
    """Chained resolvers with a recorded call log."""

    def __init__(self, resolvers: List[Resolver]) -> None:
        self._resolvers = list(resolvers)
        self.calls: List[ResolveCall] = []

    def resolve(self, key_id: bytes) -> Optional[bytes]:
        for resolver in self._resolvers:
            key = resolver.resolve_key(key_id)
            self.calls.append(ResolveCall(resolver.name, key_id, key is not None))
            if key is not None:
                return key
        return None

    @property
    def resolver_names(self) -> List[str]:
        return [r.name for r in self._resolvers]


def bluebit_chain() -> ResolverChain:
    """The chain BlueBit actually builds: Hello -> Bootstrap (no MobileData)."""
    return ResolverChain([HelloResolver(), BootstrapResolver()])


def official_chain(md_keys: Optional[Dict[bytes, bytes]] = None) -> ResolverChain:
    """The chain the official app builds: Hello -> Bootstrap -> MobileData."""
    return ResolverChain([HelloResolver(), BootstrapResolver(), MobileDataResolver(md_keys)])
