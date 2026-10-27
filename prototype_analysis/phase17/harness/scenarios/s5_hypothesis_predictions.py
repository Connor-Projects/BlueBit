"""S5 -- the prediction matrix and which explanations are separable."""

from __future__ import annotations

from harness.models.predictions import distinguishing_scenarios, pairwise, render_matrix
from harness.models.scenarios import ALL
from harness.models.world_models import MODELS


def run() -> str:
    lines = ["[SYNTHETIC] S5 -- do the competing explanations differ?"]
    lines.append(render_matrix(MODELS, ALL))
    lines.append("")
    lines.append("Separability (scenarios where the two models predict different outcomes):")
    for cmp in pairwise(MODELS, ALL):
        verdict = "INDISTINGUISHABLE" if cmp.indistinguishable else "separable"
        lines.append(f"  {cmp.a} vs {cmp.b}: {verdict} {list(cmp.differing)}")
    lines.append("")
    lines.append("  A vs C have no differing scenario => they are operational renamings,")
    lines.append("  not competing hypotheses. A/C vs D differ only on experiments that")
    lines.append("  require a factory reset or the real owner credential (both gated).")
    return "\n".join(lines)


if __name__ == "__main__":
    print(run())
