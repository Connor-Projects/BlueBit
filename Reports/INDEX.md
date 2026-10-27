# BlueBit Investigation Reports Index

This index lists all discovered investigation and summary reports copied into `G:\Dev\BlueBit\reports\`, grouped by topic. Original files remain in place.

> Note: On this Windows filesystem `reports` and the pre-existing `Reports` directory resolve to the same folder.

## Table of Contents

- [DTLS](#dtls)
- [COAP](#coap)
- [GENERAL](#general)
- [APK](#apk)
- [OTHER](#other)

## DTLS

| Original Path | Copied Path | Summary |
|---------------|-------------|---------|
| `./BlueBitApp/DTLS_Bootstrap_Probe_Implementation_Notes.md` | `./reports/dtls/DTLS_Bootstrap_Probe_Implementation_Notes.md` | DTLS Bootstrap Probe Implementation Notes |
| `./BlueBitApp/dtls_error_minus_10601_investigation.md` | `./reports/dtls/dtls_error_minus_10601_investigation.md` | GoldenGate native DTLS error `-10601` investigation |
| `./BlueBitApp/kotlin_dtls_listener_investigation.md` | `./reports/dtls/kotlin_dtls_listener_investigation.md` | Kotlin DTLS Listener / PSK Resolver Investigation |
| `./Reports/inspire3-dsng-dtls-report.md` | `./reports/dtls/inspire3-dsng-dtls-report.md` | BlueBit — DSNG/DTLS Experiment Report |
| `./Reports/inspire3-goldengate-reset-ack-report.md` | `./reports/dtls/inspire3-goldengate-reset-ack-report.md` | BlueBit — GoldenGate Reset ACK Investigation Report |

## COAP

| Original Path | Copied Path | Summary |
|---------------|-------------|---------|
| `./BlueBitApp/CoAP_ReadOnly_Probe_Implementation_Notes.md` | `./reports/coap/CoAP_ReadOnly_Probe_Implementation_Notes.md` | CoAP Read-Only Probe — Implementation Notes |
| `./BlueBitApp/bluebit_coap_api_audit.md` | `./reports/coap/bluebit_coap_api_audit.md` | BlueBit CoAP API Audit |
| `./BlueBitApp/coap_44_response_investigation.md` | `./reports/coap/coap_44_response_investigation.md` | BlueBit Probe CoAP Read-Only: `code=4.4` Investigation |
| `./BlueBitApp/next_coap_endpoint_investigation.md` | `./reports/coap/next_coap_endpoint_investigation.md` | Next CoAP Endpoint Investigation — Inspire 3 Read-Only Diagnostic |
| `./BlueBitApp/reference_coap_readonly_audit.md` | `./reports/coap/reference_coap_readonly_audit.md` | Reference CoAP Read-Only Audit — Fitbit Air Notification Bridge |

## GENERAL

| Original Path | Copied Path | Summary |
|---------------|-------------|---------|
| `./BlueBitApp/bluebit_current_state_report.md` | `./reports/general/bluebit_current_state_report.md` | BlueBit Project Phase 1 Inspection Report |
| `./BlueBitApp/mock-server/README.md` | `./reports/general/mock-server/README.md` | Fitbit Local Pairing Backend Mock Server |
| `./BlueBitApp/reference_transport_api_report.md` | `./reports/general/reference_transport_api_report.md` | Golden Gate Transport API Reference Report |
| `./README.md` | `./reports/general/README.md` | BlueBit |
| `./architecture_proposal.md` | `./reports/general/architecture_proposal.md` | BlueBit Provider-Based Architecture Proposal |
| `./fitbit_air_bridge_summary.md` | `./reports/general/fitbit_air_bridge_summary.md` | Fitbit Air Notification Bridge — Analysis Summary |

## APK

> Only markdown reports and the single analysis folder `apk_analysis_8B01Fj/` are kept here. The large JADX decompilation outputs (`Decoded/`, `tmp_hw/`) remain in their original locations and are not duplicated in this folder.

| Original Path | Copied Path | Summary |
|---------------|-------------|---------|
| `./AltaHR_Investigation_Report.md` | `./reports/apk/AltaHR_Investigation_Report.md` | Alta HR Investigation Report |
| `./BlueBitApp/mock-server/ENDPOINT_INVENTORY.md` | `./reports/apk/ENDPOINT_INVENTORY.md` | Fitbit Pairing Endpoint Inventory & Data Flow Analysis |
| `./Fitbit_Pairing_Deep_Research.md` | `./reports/apk/Fitbit_Pairing_Deep_Research.md` | Fitbit Pairing Deep Research — Inspire 3 / Charge 6 Onboarding Architecture |
| `./Fitbit_Whole_APK_Onboarding_Audit.md` | `./reports/apk/Fitbit_Whole_APK_Onboarding_Audit.md` | Fitbit Whole APK Onboarding Audit — Inspire 3 First-Time Pairing |
| `./Reports/ble-discovery-deep-dive.md` | `./reports/apk/ble-discovery-deep-dive.md` | Fitbit Android App – BLE Discovery Deep Dive |
| `./Reports/bond-control-semantics-report.md` | `./reports/apk/bond-control-semantics-report.md` | BlueBit — `bond/control` Firmware Semantics & Inspire 3 Pairing/Provisioning State Investigation |
| `./Reports/certificate-investigation-report.md` | `./reports/apk/certificate-investigation-report.md` | BlueBit — Fitbit APK Certificate/Signing-Identity Investigation Report |
| `./Reports/device-type-catalog-analysis.md` | `./reports/apk/device-type-catalog-analysis.md` | Fitbit Android App – Device-Type Catalogue Analysis |
| `./Reports/fitbit-connection-gatt-analysis.md` | `./reports/apk/fitbit-connection-gatt-analysis.md` | Fitbit Android App — Connection, GATT Discovery, and Authentication Flow Analysis |
| `./Reports/fitbit-static-analysis.md` | `./reports/apk/fitbit-static-analysis.md` | Fitbit Android App – Static Analysis Report |
| `./Reports/inspire3-gattlink-native-analysis.md` | `./reports/apk/inspire3-gattlink-native-analysis.md` | BlueBit — Inspire 3 Gattlink Native Library Analysis |
| `./Reports/scan-record-analysis.md` | `./reports/apk/scan-record-analysis.md` | Fitbit Android App – ScanRecord Analysis |
| `./apk_analysis_8B01Fj` | `./reports/apk/apk_analysis_8B01Fj/` | Decompiled APK analysis output directory. |

## OTHER

| Original Path | Copied Path | Summary |
|---------------|-------------|---------|
| `./BlueBitApp/mock-server/EXPERIMENT_REPORT.md` | `./reports/other/EXPERIMENT_REPORT.md` | BlueBit — Local Fitbit Pairing Backend Experiment Report |
| `./FitCloud_Protocol_Findings.md` | `./reports/other/FitCloud_Protocol_Findings.md` | FitCloud Pro BLE Protocol Findings |
| `./FitCloud_Refactor_Report.md` | `./reports/other/FitCloud_Refactor_Report.md` | BlueBit FitCloud Pro Integration — Final Report |
| `./FitCloud_WatchFace_Capability_Report.md` | `./reports/other/FitCloud_WatchFace_Capability_Report.md` | FitCloud Watch Face & System UI Capability Report |
| `./Gadgetbridge_Investigation_Report.md` | `./reports/other/Gadgetbridge_Investigation_Report.md` | BlueBit - Gadgetbridge Modern Fitbit Onboarding & MobileData Key Investigation |
| `./Inspire3_Gattlink_Startup_Investigation.md` | `./reports/other/Inspire3_Gattlink_Startup_Investigation.md` | Inspire 3 Gattlink Startup Investigation |
| `./Reports/inspire3-bonding-report.md` | `./reports/other/inspire3-bonding-report.md` | BlueBit Inspire 3 Bonding Experiment Report |
| `./Reports/inspire3-fbgatt-write-path-report.md` | `./reports/other/inspire3-fbgatt-write-path-report.md` | BlueBit — Fitbit fbgatt Write-Path Investigation Report |
| `./Reports/inspire3-fitbit-auth-analysis.md` | `./reports/other/inspire3-fitbit-auth-analysis.md` | BlueBit — Fitbit Inspire 3 Authentication & Connection Protocol Analysis |
| `./Reports/inspire3-fitbit-connection-reconstruction-report.md` | `./reports/other/inspire3-fitbit-connection-reconstruction-report.md` | Inspire 3 — Fitbit Connection Reconstruction Report |
| `./Reports/inspire3-fitbit-connection-reconstruction-report2.md` | `./reports/other/inspire3-fitbit-connection-reconstruction-report2.md` | Inspire 3 — Fitbit Connection Reconstruction Report |
| `./Reports/inspire3-gatt-map.md` | `./reports/other/inspire3-gatt-map.md` | Inspire 3 GATT Service Map (Pairing Mode) |
| `./Reports/inspire3-gatt-report.md` | `./reports/other/inspire3-gatt-report.md` | BlueBit Inspire 3 GATT Diagnostic Report |
| `./Reports/inspire3-gattcache-report.md` | `./reports/other/inspire3-gattcache-report.md` | BlueBit Inspire 3 GattCache Protocol Experiment Report |
| `./Reports/inspire3-gattlink-report.md` | `./reports/other/inspire3-gattlink-report.md` | BlueBit - Inspire 3 Gattlink Transport Diagnostic Report |
| `./Reports/inspire3-goldengate-init-report.md` | `./reports/other/inspire3-goldengate-init-report.md` | BlueBit — Inspire 3 GoldenGate Native Stack Initialization Report |
| `./Reports/inspire3-pairing-mode-discovery.md` | `./reports/other/inspire3-pairing-mode-discovery.md` | Inspire 3 Pairing Mode Discovery |
| `./Reports/inspire3-pairing-mode-probe-results.md` | `./reports/other/inspire3-pairing-mode-probe-results.md` | Inspire 3 Pairing Mode — Service Probe Results |
| `./Reports/inspire3-pairing-protocol-report.md` | `./reports/other/inspire3-pairing-protocol-report.md` | Inspire 3 Pairing Protocol Investigation Report |
| `./Reports/inspire3-passkey-pairing-implementation.md` | `./reports/other/inspire3-passkey-pairing-implementation.md` | Inspire 3 BLE Passkey Pairing Implementation |
| `./Reports/inspire3-trust-authentication-analysis.md` | `./reports/other/inspire3-trust-authentication-analysis.md` | BlueBit — Inspire 3 Trust/Authentication Mechanism Analysis |
| `./Reports/inspire3-trust-authentication-evidence.md` | `./reports/other/inspire3-trust-authentication-evidence.md` | BlueBit — Inspire 3 Trust/Authentication Mechanism: Evidence Collection |
| `./fitbit_bugreport.zip` | `./reports/other/fitbit_bugreport.zip` | Android bugreport archive (zip) |

