"""Does the explanation space make *different* predictions?

This is the point of the harness. It builds the prediction matrix over the
competing explanations and asserts exactly which pairs are separable and which
are not. It also falsifies the naive "the three are interchangeable" model.
"""

from __future__ import annotations

import unittest

from harness.models.predictions import (
    build_matrix,
    distinguishing_scenarios,
    observed_check,
    pairwise,
    render_matrix,
)
from harness.models.scenarios import ALL, by_key
from harness.models.world_models import (
    MODEL_A_CREDENTIAL,
    MODEL_C_RESOURCE_POLICY,
    MODEL_D_OWNERSHIP,
    MODEL_E_EXPIRY,
    MODEL_NAIVE,
    MODELS,
    transport,
)

SUBSTANTIVE = (MODEL_A_CREDENTIAL, MODEL_C_RESOURCE_POLICY, MODEL_D_OWNERSHIP, MODEL_E_EXPIRY)


class TestObservedAnchors(unittest.TestCase):
    def test_all_substantive_models_reproduce_observations(self):
        matrix = build_matrix(SUBSTANTIVE, ALL)
        self.assertEqual(observed_check(matrix, ALL), [])

    def test_transport_is_established_for_observed_anchors(self):
        for key in ("OBS_PAIR_DISPLAY", "OBS_SYNC_CONFIG", "OBS_BOND_CONTROL"):
            res = transport(by_key(key))
            self.assertTrue(res.established, key)
            self.assertEqual(res.session_kind, "BOOTSTRAP", key)

    def test_naive_interchange_model_is_falsified(self):
        matrix = build_matrix((MODEL_NAIVE,), ALL)
        notes = observed_check(matrix, ALL)
        self.assertTrue(notes, "naive model must contradict an observed anchor")
        self.assertTrue(any("OBS_BOND_CONTROL" in n for n in notes))


class TestSeparability(unittest.TestCase):
    def test_a_and_c_are_not_separable(self):
        self.assertEqual(distinguishing_scenarios(MODEL_A_CREDENTIAL, MODEL_C_RESOURCE_POLICY, ALL), [])

    def test_a_and_d_differ_only_on_forbidden_or_unavailable_experiments(self):
        diff = set(distinguishing_scenarios(MODEL_A_CREDENTIAL, MODEL_D_OWNERSHIP, ALL))
        self.assertEqual(diff, {"DIS_FRESH_BOOTSTRAP_BOND", "DIS_OWNED_OFFICIAL_MD_BOND"})
        for key in diff:
            self.assertTrue(by_key(key).reachability.startswith("REQUIRES"))

    def test_c_and_d_differ_on_the_same_experiments(self):
        self.assertEqual(
            set(distinguishing_scenarios(MODEL_C_RESOURCE_POLICY, MODEL_D_OWNERSHIP, ALL)),
            {"DIS_FRESH_BOOTSTRAP_BOND", "DIS_OWNED_OFFICIAL_MD_BOND"},
        )

    def test_naive_model_differs_on_the_wrong_key_experiment(self):
        diff = set(distinguishing_scenarios(MODEL_A_CREDENTIAL, MODEL_NAIVE, ALL))
        self.assertIn("DIS_SYNTH_MD_WRONGKEY_BOND", diff)

    def test_expiry_is_not_separable_from_ownership_by_protocol(self):
        # E is only distinguished from D by source inspection, not by protocol.
        self.assertEqual(distinguishing_scenarios(MODEL_D_OWNERSHIP, MODEL_E_EXPIRY, ALL), [])

    def test_pair_display_never_discriminates(self):
        for a, b in [(m1, m2) for i, m1 in enumerate(MODELS) for m2 in MODELS[i + 1:]]:
            diff = distinguishing_scenarios(a, b, ALL)
            self.assertNotIn("OBS_PAIR_DISPLAY", diff)

    def test_no_currently_permitted_scenario_separates_a_from_c(self):
        self.assertEqual(pairwise(MODELS, ALL)[0].differing, tuple())


class TestMatrixRendering(unittest.TestCase):
    def test_matrix_renders(self):
        text = render_matrix(SUBSTANTIVE, ALL)
        self.assertIn("OBS_BOND_CONTROL", text)
        self.assertIn("DIS_FRESH_BOOTSTRAP_BOND", text)


if __name__ == "__main__":
    unittest.main()
