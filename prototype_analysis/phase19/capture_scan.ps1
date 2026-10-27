# Phase 19 helper: quick greps into the live capture.
param([string]$Path='G:\Dev\BlueBit\prototype_analysis\phase19\captures\phase19_probe_run_2026-10-25_2320.logcat.txt')
$tags='BlueBitDTLSProbe|BlueBitGG_Stack|BlueBitGG_Tls|BlueBitGG_Bridge|BlueBitGG_Diag|DTLS_BOOTSTRAP_PROBE|BlueBit\b'
"--- probe-relevant lines so far ---"
Select-String -Path $Path -Pattern $tags | ForEach-Object { "{0,6}: {1}" -f $_.LineNumber, $_.Line.Trim() }
"--- degeneracy check for 'DTLS_BOOTSTRAP_PROBE result=' ---"
(Select-String -Path $Path -SimpleMatch -Pattern 'DTLS_BOOTSTRAP_PROBE result=').Count
