# Phase 17 — Harness Results (offline, synthetic)

**Status:** COMPLETE (offline; nothing built, installed, or connected)
**Date:** 2026-10-19
**Scope:** Implements the Layer-L1/L2 harness from
`PHASE17_AUTH_HARNESS_PLAN.md` §6 as **new files only** under
`prototype_analysis/phase17/harness/`. No existing source, evidence, APK, or
report was modified; Git state preserved (HEAD `7d2342fd…`, stash 0, 78 status
entries).

> **Every result below is SYNTHETIC.** The harness is a model. It does not
> contact the tracker, read a secret, or reproduce the Fitbit implementation or
> backend. A passing test validates the **model's logic**, not the tracker.

---

## 1. What was built

| Piece | Path | Notes |
|-------|------|-------|
| Python harness + tests | `harness/` | 54 unit tests, runnable offline with `python run_all.py` |
| JVM (Kotlin) tests | `harness/jvm/` | 4 files; **not run** (no build permitted). 2 target real `commonMain` classes |

Run:

```
python prototype_analysis/phase17/harness/run_all.py
```

Result: `Ran 54 tests … OK` (0 failures, 0 errors).

The JVM files are held under `harness/jvm/` (rather than the plan's suggested
`BlueBitApp/app/src/desktopTest/...`) so the working tree is unchanged. Placement
and the run command are in `harness/jvm/README.md`.

---

## 2. The prediction matrix (the core output)

Scenarios: `OBS_*` are captured/live facts; `DIS_*` are not-yet-observed
experiments. Models: **A** missing credential, **C** resource policy by session
identity, **D** ownership/pairing-state, **E** key expiry, **N** naive
interchange (negative control).

```
model      | OBS_PAIR_DISPLAY | OBS_SYNC_CONFIG | OBS_BOND_CONTROL | DIS_FRESH_BOOTSTRAP_BOND | DIS_OWNED_OFFICIAL_MD_BOND | DIS_SYNTH_MD_WRONGKEY_BOND
A          | 2.04 app=0       | 4.01 app=5378   | 4.01 app=5378    | 4.01 app=5378            | 2.04 app=0                 | 4.01 app=5378
C          | 2.04 app=0       | 4.01 app=5378   | 4.01 app=5378    | 4.01 app=5378            | 2.04 app=0                 | 4.01 app=5378
D          | 2.04 app=0       | 4.01 app=5378   | 4.01 app=5378    | 2.04 app=0               | 4.01 app=5378              | 4.01 app=5378
E          | 2.04 app=0       | 4.01 app=5378   | 4.01 app=5378    | 2.04 app=0               | 4.01 app=5378              | 4.01 app=5378
N          | 2.04 app=0       | 2.04 app=0      | 2.04 app=0       | 4.01 app=5378            | 2.04 app=0                 | 2.04 app=0
```

Separability (scenarios where two models predict **different observables**):

```
A vs C : INDISTINGUISHABLE        []                       <- renamings, not competitors
A vs D : separable                [DIS_FRESH_BOOTSTRAP_BOND, DIS_OWNED_OFFICIAL_MD_BOND]
C vs D : separable                [DIS_FRESH_BOOTSTRAP_BOND, DIS_OWNED_OFFICIAL_MD_BOND]
D vs E : INDISTINGUISHABLE        []                       <- E ruled out by source, not protocol
A vs N : separable                [OBS_SYNC_CONFIG, OBS_BOND_CONTROL, DIS_SYNTH_MD_WRONGKEY_BOND]
```

---

## 3. Hypotheses the tests CAN distinguish

* **The naive "interchangeable" explanation is falsified.** Treating
  "missing credential", "session identity", and "resource policy" as
  interchangeable predicts a `2.04` for `bond/control` and `sync/config` on the
  observed bootstrap session — contradicted by the captured `4.01`. The tests
  assert this contradiction (`test_naive_interchange_model_is_falsified`).
* **A transport-level explanation of the `4.01` is falsified.** A `4.01` is a
  CoAP reply, which requires an established DTLS session; the model shows the
  session is established and the denial is application-level
  (`test_transport_failure_explanation_is_contradicted`,
  `test_bond_control_is_application_level_denial`).
* **The expiry explanation (E) is eliminated — but only by source inspection.**
  `4.01 → 5378 (RF_ERR_KEY_EXPIRED)` is unconditional and ignores any device
  `ExtendedError` (`CoapResponseUtils.java:174-186`), so it cannot evidence a real
  expiry. By protocol alone, E is indistinguishable from D
  (`test_expiry_is_not_separable_from_ownership_by_protocol`).
* **A and C are operationally the same explanation.** Over all scenarios the two
  produce identical observables; they are two names for "the session is not
  owner-authenticated" (`test_a_and_c_are_not_separable`,
  `test_credential_and_session_policies_agree`).

## 4. Hypotheses that REMAIN UNRESOLVED

* **A/C vs D.** Both reproduce every observation. They diverge **only** on:
  * `DIS_FRESH_BOOTSTRAP_BOND` — needs a **factory reset** of an unowned device
    (forbidden); and
  * `DIS_OWNED_OFFICIAL_MD_BOND` — needs a **MobileData-authenticated session**
    (the real key, i.e. the official app or the real credential; not available).

  Both are gated, so on currently permitted evidence the tracker-level cause is
  **not decided** by this harness — by construction.

The tests also confirm the *client path* is identity-independent (T4): the same
request construction yields `pair/display → 2.04` and `bond/control → 4.01`
regardless of the session label, so the difference is the device's response, not
BlueBit's request.

---

## 5. Synthetic / provenance guards

* `hello` / `BOOTSTRAP` are public, hard-coded values (not secrets).
* All `MD-*` **keys** are synthetic and fake. `MD-A376B03C-6AF89570` appears only
  as a **non-secret identifier**; its key is absent and never fabricated.
* `OPAQUE_SHARED_KEY_PLACEHOLDER` models only "both ends hold the same value"; it
  carries no information about the real key.
* The harness's framing, DTLS, and firmware policy are **models**, labelled as
  such in-source; the DTLS layer performs no cryptography.
* Field-fidelity (T8) decodes the **real captured bytes** verbatim and asserts the
  harness's use of them is exact; it does not reinterpret them.

---

## 6. The smallest safe next step

* **Offline, still gated only by build permission:** run the four JVM tests
  (`./gradlew :app:desktopTest --tests "bluebit.fitbit.phase17.*"`). This compiles
  JVM/desktop code only — **not** an APK — and regression-locks the mapping,
  resolver, DTLS-recovery, and transport rules. Two of the four target the real
  `commonMain` classes; the other two are mirrors because the real resolver chain
  is `androidMain`-only (see `harness/jvm/README.md`).
* **Device, if ever approved:** the *smallest* discriminating action is **E4**, a
  **read-only** recorder of the PSK identities the tracker requests (passive
  observation; no `createBond()`, no reset, no writes). It makes the
  BOOTSTRAP-only conclusion record-grade but still cannot separate A/C from D.
  Separating A/C from D requires the two gated experiments in §4.

No further **offline** step adds discriminating power: the compiler of the
hypothesis space is done.

---

## 7. Deliverables index (SHA-256)

```
0a724bfa282729a16385308cabe3ad3f2e11cf98f9d2e651a6c74887fbec3ecc  __init__.py
60e75557bc0563d5232f04f0ac5f31d0115f206a4ffc34fa6ee77160f383750f  README.md
17a1d63831f9e0af95a849f9d5d29f2ee017444e4da7ef9076a677c2e5914cc2  run_all.py
f828e4e99376b1853c27ef83bd8ef66ceaecc9d4924a2682339e519eabcfc519  synthetic_ids.py
f8170899bbcdac7a875e573a7224f3bf9430ad9a2f7904391248db6483ef5abe  mock_stack/__init__.py
21d5cadfb6b959e394231dfd3830dfd73d2ed3dc550b818952d5eda2a66c1728  mock_stack/coap.py
e2428ff23dae27e19f640d0cfb8bf471467430e3925d9bba8149ed173905d381  mock_stack/coap_router.py
edddde77e66a563a3db1c4bd3877b804b37c51a4d96812dac812b408acfb6bbb  mock_stack/dtls_psk.py
9ae86dd7c178373a419c92544b7eddfea51a99053cc9342b9125f0589ec0e5dd  mock_stack/gattlink_framing.py
717441befef86aba49df63309219bf6b2d0adc64933b7041bae83a8d0e789f72  mock_stack/mapping.py
1b27c7e4791a4ead0df70aefd94b4d3fd2787396fd20377804ab66e2efa67dae  mock_stack/resolver.py
ed89e02d245ced0b883ce3d6c7dd2730432f2aca9c9f6cf110ab05db33f3ed68  mock_stack/tx_serializer.py
49931c78d45188654c5ab1375e8503b421dba30a0297e6bdca4b1e19d1dd323c  models/__init__.py
be740a9542f7e5d0e6b4fa5f26b027175eebdf7eeb38744d4a06f36552cecaed  models/predictions.py
ae6c754afc23231789a86411f3038ef159ecec294d0deffef857a826d761e1b7  models/scenarios.py
5b7df29684b44b2f8002e0a6fc0d835efe75fbe8d941612accfe1a1c40c7932f  models/world_models.py
bde1447c3fb53eef21579028757b538bf7b02408a1c19b57b2a48ffabb914192  scenarios/__init__.py
80f6358f29cab1b6fdd34ac47105ec170912e8166ca2a367c26699267e35bc15  scenarios/s1_bootstrap_fallback.py
85fc00d4216d1a2112c52d48cae83c57cdeb2edc4a21216495cb3b066c2e1396  scenarios/s2_resource_policy.py
6ae39a958f395c308deb80dbc0a5f743bb51da8e7bda64b3b6aae8887f8ff3a4  scenarios/s3_md_session.py
ba83edcb42e8bd92c620bf4128b88ee4070c9c798f5a1a4235e07b11b8f77c04  scenarios/s4_extended_error.py
c412d418a1a3ae2f9bdbe26561691c915e5447ada97cb139066ebbec9362c421  scenarios/s5_hypothesis_predictions.py
7c6c350a942dd32d47941b1171c27f35ad1dba262b440ecb1e7dcc487b4c8be3  tests/__init__.py
21f2caa01f4cb0b9ac23cfdf97aa1e24226bc71c5196e44a890eb2a075660f44  tests/test_field_fidelity.py
59fa156c776ceb3d310c01f09a700fd15879d96bf311fc925cd7609f760fe202  tests/test_framing.py
ab4a25de55787c1b663552e3ae8e04f09887c5217ae862ff87fa0692c268eaf1  tests/test_handshake.py
78dad5c692f6cbe10d83e958dbeba81cbb322dad1fd69ee251699b36da976edc  tests/test_mapping.py
d8e7efcd9900090446e749e77c6c7e0a70d8d84b5a29752af7f7b23390b6790d  tests/test_resolver.py
1462ee4b327cca8af10f0b672ca80927f3ab224db9abbbe9e23f6d1a46eb4c7d  tests/test_resource_policy.py
e9a749801682261f8055b872315667faccfe0bc73f08c77e1109240e15eca114  tests/test_world_models.py
9c6f6282397018aa1b3ce0ba3ec92819ff4bb56a542eeaec9241a8d87f126b2d  jvm/Phase17DtlsResetTest.kt
88ddf2edd87fda8014a8541f0db0f7d26a6531825e5f7c7b6fa9563e33a57deb  jvm/Phase17MappingTest.kt
c2670416426e676b07cdcd2ae7aa12b706ef96600dc07120a75d20ea51fbbbca  jvm/Phase17ResolverWalkTest.kt
ba1c6b9bda5790311f00eac116797b07c85a37d6d1556d31287fc6194e63bc1d  jvm/Phase17TransportInvariantTest.kt
fc1df96cf1584c943fadc7d73b7560c5482b96de7fdd825dcede1bff2b402fed  jvm/README.md
```

All paths are relative to `prototype_analysis/phase17/harness/`.
