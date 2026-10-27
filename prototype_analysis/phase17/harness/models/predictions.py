"""Prediction matrix and the "can these explanations be told apart?" analysis.

The central deliverable of the harness: for every (model, scenario) pair, record
the predicted observable, then compute for each pair of models the set of
scenarios on which they differ. An *empty* distinguishing set means the two
explanations are operationally equivalent over everything we can currently
observe -- they are not competing hypotheses but renamings of one another.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Sequence, Tuple

from .scenarios import ALL, Outcome, Scenario
from .world_models import MODELS, WorldModel


def predict(model: WorldModel, scenario: Scenario) -> Outcome:
    return model.predict(scenario)


def build_matrix(models: Sequence[WorldModel] = MODELS,
                 scenarios: Sequence[Scenario] = ALL) -> Dict[str, Dict[str, Outcome]]:
    return {m.key: {s.key: m.predict(s) for s in scenarios} for m in models}


def distinguishing_scenarios(a: WorldModel, b: WorldModel,
                             scenarios: Sequence[Scenario] = ALL) -> List[str]:
    return [s.key for s in scenarios
            if a.predict(s).observable() != b.predict(s).observable()]


@dataclass(frozen=True)
class PairComparison:
    a: str
    b: str
    differing: Tuple[str, ...]

    @property
    def indistinguishable(self) -> bool:
        return not self.differing


def pairwise(models: Sequence[WorldModel] = MODELS,
             scenarios: Sequence[Scenario] = ALL) -> List[PairComparison]:
    out: List[PairComparison] = []
    for i, a in enumerate(models):
        for b in models[i + 1:]:
            out.append(PairComparison(a.key, b.key,
                                      tuple(distinguishing_scenarios(a, b, scenarios))))
    return out


def observed_check(matrix: Dict[str, Dict[str, Outcome]],
                   scenarios: Sequence[Scenario] = ALL) -> List[str]:
    """Return a list of human-readable consistency notes for observed anchors."""
    notes: List[str] = []
    for s in scenarios:
        if not s.observed:
            continue
        for model_key, row in matrix.items():
            outcome = row[s.key]
            if outcome.transport != s.observed_transport:
                notes.append(
                    f"{model_key} predicts transport={outcome.transport} but "
                    f"{s.key} was observed transport={s.observed_transport}"
                )
            from harness.mock_stack.coap import code_name
            if outcome.status is not None and code_name(outcome.status) != s.observed_status:
                notes.append(
                    f"{model_key} predicts {code_name(outcome.status)} but "
                    f"{s.key} was observed {s.observed_status}"
                )
    return notes


def render_matrix(models: Sequence[WorldModel] = MODELS,
                  scenarios: Sequence[Scenario] = ALL) -> str:
    matrix = build_matrix(models, scenarios)
    header = ["model"] + [s.key for s in scenarios]
    widths = [max(len(h), 10) for h in header]
    widths[0] = max(widths[0], 6)
    lines = [" | ".join(h.ljust(widths[i]) for i, h in enumerate(header))]
    lines.append("-+-".join("-" * widths[i] for i in range(len(header))))
    for m in models:
        cells = [m.key.ljust(widths[0])]
        for i, s in enumerate(scenarios, start=1):
            cells.append(matrix[m.key][s.key].render().ljust(widths[i]))
        lines.append(" | ".join(cells))
    return "\n".join(lines)
