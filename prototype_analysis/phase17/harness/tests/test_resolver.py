"""T2 -- resolver-chain behaviour (the server/hub side)."""

from __future__ import annotations

import unittest

from harness.mock_stack.resolver import (
    BootstrapResolver,
    HelloResolver,
    MobileDataResolver,
    bluebit_chain,
    official_chain,
)
from harness.synthetic_ids import (
    BOOTSTRAP_ID,
    BOOTSTRAP_KEY,
    HELLO_ID,
    HELLO_KEY,
    SYNTHETIC_MD_ID,
    SYNTHETIC_MD_KEY,
    REAL_MD_ID,
)


class TestResolverChain(unittest.TestCase):
    def test_bluebit_chain_has_no_mobile_data(self):
        chain = bluebit_chain()
        self.assertEqual(
            chain.resolver_names,
            ["HelloTlsKeyResolver", "BootstrapTlsKeyResolver"],
        )
        self.assertNotIn("MobileDataTlsKeyResolver", chain.resolver_names)

    def test_public_identities_resolve(self):
        chain = bluebit_chain()
        self.assertEqual(chain.resolve(HELLO_ID), HELLO_KEY)
        self.assertEqual(chain.resolve(BOOTSTRAP_ID), BOOTSTRAP_KEY)

    def test_bluebit_cannot_resolve_md_identity(self):
        chain = bluebit_chain()
        self.assertIsNone(chain.resolve(REAL_MD_ID))
        self.assertIsNone(chain.resolve(SYNTHETIC_MD_ID))

    def test_unknown_identity_exhausts_the_chain(self):
        chain = bluebit_chain()
        self.assertIsNone(chain.resolve(b"nope"))
        self.assertEqual(
            [c.resolver for c in chain.calls],
            ["HelloTlsKeyResolver", "BootstrapTlsKeyResolver"],
        )
        self.assertTrue(all(not c.found for c in chain.calls))

    def test_official_chain_resolves_registered_md(self):
        chain = official_chain({SYNTHETIC_MD_ID: SYNTHETIC_MD_KEY})
        self.assertEqual(chain.resolve(SYNTHETIC_MD_ID), SYNTHETIC_MD_KEY)
        # ordered after the two public resolvers
        self.assertEqual(
            [c.resolver for c in chain.calls],
            ["HelloTlsKeyResolver", "BootstrapTlsKeyResolver", "MobileDataTlsKeyResolver"],
        )

    def test_mobile_data_resolver_is_specific(self):
        r = MobileDataResolver({SYNTHETIC_MD_ID: SYNTHETIC_MD_KEY})
        self.assertEqual(r.resolve_key(SYNTHETIC_MD_ID), SYNTHETIC_MD_KEY)
        self.assertIsNone(r.resolve_key(REAL_MD_ID))
        self.assertIsNone(BootstrapResolver().resolve_key(HELLO_ID))
        self.assertIsNone(HelloResolver().resolve_key(BOOTSTRAP_ID))


if __name__ == "__main__":
    unittest.main()
