# Phase 17 — offline authentication test harness

A pure-Python oracle + regression fixture that models the **shape** of the
BlueBit <-> Inspire 3 protocol and — the point of the exercise — asks whether the
competing explanations for `PUT bond/control -> 4.01` make **different
predictions**.

> **Everything this harness produces is SYNTHETIC.** It is a model. It does not
> contact a device, read a secret, build an APK, or reproduce the Fitbit tracker
> or backend. A passing test validates the model's *logic*, not the tracker.

## What it can and cannot establish

**Can** (offline, now):

* that the explanations "missing credential" (**A**) and "resource policy keyed on
  session identity" (**C**) are **operationally identical** — they are renamings,
  not competitors (0 distinguishing scenarios);
* that "ownership / pairing-state gating" (**D**) is the only substantive
  explanation that makes a *different* prediction, and it does so **only** on
  experiments that are currently forbidden/unavailable;
* that a naive "the three are interchangeable" model is **falsified** by the
  transport evidence (a `4.01` is a CoAP reply, so the DTLS session existed);
* that `4.01 -> RF_ERR_KEY_EXPIRED (5378)` is unconditional, so it is **not**
  evidence of a real key expiry;
* exact field fidelity against the captured CoAP bytes.

**Cannot**: decide between A/C and D on the tracker. That needs one of the gated
experiments (see `../HARNESS_RESULTS.md`).

## Run it

```
python prototype_analysis/phase17/harness/run_all.py
```

No dependencies beyond the standard library and the repository's existing
`offline_poc/coap_parser.py` (imported unmodified). 54 tests.

## Layout

```
harness/
  synthetic_ids.py            public values + clearly-fake synthetic MD ids; real id is identifier-only
  mock_stack/
    coap.py                   CoAP encoder + re-export of offline_poc parser
    gattlink_framing.py       1-byte prefix + IPv4 framing; reassembly on IPv4 total length
    resolver.py               Hello / Bootstrap / MobileData resolver chain model
    dtls_psk.py               PSK identity-negotiation model (no real crypto)
    coap_router.py            mock firmware resource policy (C and D), credential-presence policy (A)
    mapping.py                CoAP -> Airlink error mapping (mirror of CoapResponseUtils)
    tx_serializer.py          one-outstanding-write serializer analog
  models/
    scenarios.py              observed anchors + discriminating scenarios; Outcome
    world_models.py           explanations A/C/D/E and the naive negative control
    predictions.py            prediction matrix + separability analysis
  scenarios/                  runnable synthetic traces (S1..S5)
  tests/                      unittest suite (T1..T8)
  jvm/                        pure-JVM Kotlin tests + placement notes
  run_all.py                  traces + full test run
```

## Test <-> plan matrix

| Plan | Where | Covers |
|------|-------|--------|
| T1 | `tests/test_handshake.py` | bootstrap fallback; alert 115 on the owner identity |
| T2 | `tests/test_resolver.py` (+ JVM mirror) | `Hello -> Bootstrap`; `MD-* -> null`; requested-id log |
| T3 | `tests/test_resource_policy.py` | `pair/display -> 2.04`, `bond/control -> 4.01` under BOOTSTRAP |
| T4 | `tests/test_resource_policy.py` | identity-independence of the client path |
| T5 | `tests/test_mapping.py` (+ JVM) | `4.01` masking of `ExtendedError` |
| T6 | `tests/test_mapping.py` (+ JVM) | non-`4.01` `ExtendedError` passthrough |
| T7 | `tests/test_framing.py` (+ JVM) | one-outstanding-write; framing round-trip |
| T8 | `tests/test_field_fidelity.py` | decode of captured bytes == captured decode |
| — | `tests/test_world_models.py` | separability of A/C/D/E and falsification of the naive model |

## Provenance rules

* `hello` / `BOOTSTRAP` are public, hard-coded values (not secrets).
* Every `MD-*` **key** here is synthetic and fake.
* `MD-A376B03C-6AF89570` is reproduced as a **non-secret identifier only**; its
  key is absent and never fabricated.
* The real device key is never used; `OPAQUE_SHARED_KEY_PLACEHOLDER` merely models
  "both ends hold the same value" for scenarios that need a completed session.
