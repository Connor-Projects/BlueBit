# Offline PoC

Pure-Python offline proof of concept for parsing and reassembling Fitbit Inspire 3
CoAP traffic from the decrypted capture.

## Files

- `coap_parser.py` — RFC 7252 CoAP parser with Block option support (RFC 7959).
- `blockwise_reassembler.py` — Reassemble block-wise transfers by token or inferred path.
- `sync_dump_reconstructor.py` — Reconstruct `/sync/dump` payloads from the captured samples.
- `test_offline_poc.py` — Unit tests using captured examples.

## Usage

```powershell
cd G:\Dev\BlueBit\prototype_analysis\offline_poc
python sync_dump_reconstructor.py
python test_offline_poc.py
```

The PoC reads `G:\Dev\BlueBit\inspire3_decryption_analysis\decrypted_coap_samples.csv`
and the corresponding `sample_frame*.bin` files.
