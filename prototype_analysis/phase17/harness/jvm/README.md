# Phase 17 — pure-JVM (Kotlin) tests

These four files are **new, standalone Kotlin test sources**. They are kept here
(inside the approved `prototype_analysis/phase17/` tree) so that adding them does
**not** change the Git working tree. They are **not compiled or run** by this
investigation — the standing constraints forbid a build. They are ready to drop
into the app's `desktopTest` source set.

## Where they go

Copy the four `*.kt` files to:

```
BlueBitApp/app/src/desktopTest/kotlin/bluebit/fitbit/phase17/
```

Then run (this compiles JVM/desktop code only; it does **not** build an APK):

```
./gradlew :app:desktopTest --tests "bluebit.fitbit.phase17.*"
```

## What each file covers

| File | Plan item | Targets | Runs against |
|------|-----------|---------|--------------|
| `Phase17MappingTest.kt` | T5, T6 | `4.01 -> 5378` masking, `5.03 -> 4353`, `ExtendedError` passthrough | A **replica** of the documented rule (real bindings are Android-only) |
| `Phase17ResolverWalkTest.kt` | T2 | `Hello -> Bootstrap`, `MD-* -> null`, requested-ids log | A **mirror** of `TlsKeyResolver.resolve` (real classes are `androidMain`) |
| `Phase17DtlsResetTest.kt` | T1 (L2) | `1 -> 3(-10601) -> 0 -> 1 -> 2` recovers; `-10601` transient | The **real** `DtlsProbeStateMachine` (`commonMain`) |
| `Phase17TransportInvariantTest.kt` | T7 (L2) | one outstanding write; byte-identical payloads; no silent success | The **real** `GattlinkTxSerializer` (`commonMain`) |

## Classpath limitation (important, and honest)

`desktopTest` depends on `desktopMain` + `commonMain` only. Confirmed from
`BlueBitApp/app/build.gradle.kts` (source sets: `androidMain`, `commonMain`,
`desktopMain`, `desktopTest` — there is **no** `androidUnitTest`). Therefore:

* The **real** `TlsKeyResolver`, `HelloTlsKeyResolver`, `BootstrapTlsKeyResolver`,
  `TlsKeyResolverRegistry`, `CoapEndpoint` and `OutgoingRequestBuilder` live in
  `androidMain` and **cannot be referenced** from `desktopTest` (they also touch
  `android.util.Log`). The resolver test is consequently a mirror.
* To exercise the real resolver chain on a JVM, either move the resolvers to
  `commonMain`, or add an `androidUnitTest` source set (Robolectric). That is a
  source-tree change and is **out of scope** here; it is recorded as a follow-up.

The two files that **do** run against real code (`DtlsProbeStateMachine`,
`GattlinkTxSerializer`) are the strongest of the four for that reason.
