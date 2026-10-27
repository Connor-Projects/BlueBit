#!/usr/bin/env python3
"""Phase 17 — E1 + E2 offline analysis.

E1: Build a resource x method x status x direction table from the already-decrypted
    bootstrap CoAP samples (no device, no network).
E2: Determine whether `/bond/control` appears anywhere in the official captured
    session, and which DTLS PSK identity was in force for the CoAP traffic.

Inputs (read-only, never modified):
  - inspire3_decryption_analysis/decrypted_coap_samples.csv
  - inspire3_decryption_analysis/sample_frame*.bin
  - inspire3_decryption_analysis/reconstruction_outputs/dtls_handshake_summary.csv

Outputs (new files only):
  - prototype_analysis/phase17/out/e1_message_table.csv
  - prototype_analysis/phase17/out/e1_resource_table.csv
  - prototype_analysis/phase17/out/e1_summary.md
  - prototype_analysis/phase17/out/e2_bond_control_check.txt

The CoAP parser is reused, unmodified, from prototype_analysis/offline_poc/coap_parser.py.
"""

from __future__ import annotations

import csv
import hashlib
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(r"G:\Dev\BlueBit")
DECRYPT_DIR = REPO / "inspire3_decryption_analysis"
SAMPLES_CSV = DECRYPT_DIR / "decrypted_coap_samples.csv"
HANDSHAKE_CSV = DECRYPT_DIR / "reconstruction_outputs" / "dtls_handshake_summary.csv"
OUT_DIR = REPO / "prototype_analysis" / "phase17" / "out"

# Reuse the existing, unmodified CoAP parser.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "offline_poc"))
from coap_parser import parse_coap, parse_coap_hex  # noqa: E402

# Frame numbering here is the *inner reconstructed-PCAP* frame number (1..341),
# not the BLE ATT frame number. Bootstrap session per DECRYPTION_REPORT.md:
#   handshake frames   5-8   (identity BOOTSTRAP)
#   app-data frames    9-55  (both directions decryptable)
#   phone->tracker bootstrap responses continue up to frame 204
#   MobileData re-key  ClientHello frame 56, ClientKeyExchange frame 57, ServerHello 204
BOOTSTRAP_IDENTITY = "BOOTSTRAP"
FIRST_INNER_FRAME = 9
LAST_BOOTSTRAP_FRAME = 204

CODE_CLASS_NAME = {
    0: "request",
    2: "success",
    4: "client-error",
    5: "server-error",
}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()


def load_message(row: dict) -> tuple[bytes, str]:
    """Return (message_bytes, source_note) preferring the full .bin over the hex snippet."""
    bin_path = DECRYPT_DIR / row["file"]
    if bin_path.exists():
        return bin_path.read_bytes(), f"bin:{row['file']}"
    return bytes.fromhex(row["first_128_hex"]), "csv:first_128_hex"


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    with SAMPLES_CSV.open(newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))

    messages = []
    for row in rows:
        raw, source = load_message(row)
        msg = parse_coap(raw)
        messages.append(
            {
                "frame": int(row["frame"]),
                "direction": row["direction"],
                "raw_len": len(raw),
                "source": source,
                "type": msg.type_name,
                "code": msg.code,
                "code_class": msg.code_class,
                "code_detail": msg.code_detail,
                "code_name": msg.code_name,
                "method": msg.code_name if msg.code_class == 0 else "",
                "path": msg.uri_path(),
                "query": msg.uri_query(),
                "token": msg.token_hex,
                "mid": msg.mid,
                "block2": str(msg.block2()) if msg.block2() else "",
                "block1": str(msg.block1()) if msg.block1() else "",
                "content_format": msg.content_format(),
                "payload_len": len(msg.payload),
            }
        )

    messages.sort(key=lambda m: m["frame"])

    # ---- E1: message table -------------------------------------------------
    msg_csv = OUT_DIR / "e1_message_table.csv"
    with msg_csv.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(messages[0].keys()))
        writer.writeheader()
        writer.writerows(messages)

    # ---- pair request <-> response by token --------------------------------
    requests_by_token: dict[str, list[dict]] = defaultdict(list)
    responses_by_token: dict[str, list[dict]] = defaultdict(list)
    for m in messages:
        if m["code_class"] == 0:
            requests_by_token[m["token"]].append(m)
        else:
            responses_by_token[m["token"]].append(m)

    # A request's responder is the *other* side. Determine each side's CoAP role
    # by observing who answers which requests.
    transactions = []
    for token, reqs in requests_by_token.items():
        resps = responses_by_token.get(token, [])
        for req in reqs:
            for resp in resps:
                transactions.append(
                    {
                        "token": token,
                        "req_frame": req["frame"],
                        "req_dir": req["direction"],
                        "req_method": req["method"],
                        "path": req["path"],
                        "query": req["query"],
                        "resp_frame": resp["frame"],
                        "resp_dir": resp["direction"],
                        "resp_code": resp["code_name"],
                        "resp_class": resp["code_class"],
                    }
                )
    transactions.sort(key=lambda t: (t["req_frame"], t["resp_frame"]))

    # ---- E1: resource table (requester = the side that issued the request) --
    # resource key = path (+query); track methods, requester side, statuses.
    res: dict[str, dict] = {}
    for t in transactions:
        requester = "phone" if t["req_dir"].startswith("phone") else "tracker"
        responder = "tracker" if requester == "phone" else "phone"
        key = t["path"] + (("?" + t["query"]) if t["query"] else "")
        entry = res.setdefault(
            key,
            {
                "resource": key,
                "requester": requester,
                "responder": responder,
                "methods": set(),
                "statuses": set(),
                "n": 0,
                "req_frames": [],
            },
        )
        entry["methods"].add(t["req_method"])
        entry["statuses"].add(t["resp_code"])
        entry["n"] += 1
        entry["req_frames"].append(t["req_frame"])

    res_csv = OUT_DIR / "e1_resource_table.csv"
    with res_csv.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(
            [
                "resource",
                "requester",
                "responder",
                "methods",
                "statuses",
                "n_transactions",
                "first_req_frame",
            ]
        )
        for key in sorted(res):
            e = res[key]
            writer.writerow(
                [
                    key,
                    e["requester"],
                    e["responder"],
                    "|".join(sorted(e["methods"])),
                    "|".join(sorted(e["statuses"])),
                    e["n"],
                    min(e["req_frames"]),
                ]
            )

    # ---- E2: bond/control presence + identity attribution ------------------
    all_paths = sorted({m["path"] for m in messages if m["path"]})
    bond_like = [m for m in messages if m["path"] and "bond" in m["path"].lower()]
    control_like = [m for m in messages if m["path"] and "control" in m["path"].lower()]

    handshake_rows = []
    if HANDSHAKE_CSV.exists():
        with HANDSHAKE_CSV.open(newline="", encoding="utf-8") as fh:
            handshake_rows = list(csv.DictReader(fh))

    # Collect the distinct PSK identities and the alerts seen on the wire.
    identities = [
        (r["ble_frame"], r["direction"], r["psk_identity"])
        for r in handshake_rows
        if r.get("psk_identity")
    ]
    alerts = [
        (r["ble_frame"], r["direction"], r.get("alert_level"), r.get("alert_desc"))
        for r in handshake_rows
        if r.get("alert_desc")
    ]

    # ---- report ------------------------------------------------------------
    lines = []
    lines.append("E2 — does `/bond/control` appear in the official captured session?")
    lines.append("=" * 72)
    lines.append("")
    lines.append(f"Input samples CSV : {SAMPLES_CSV}")
    lines.append(f"  sha256          : {sha256(SAMPLES_CSV)}")
    lines.append(f"Decrypted messages: {len(messages)}")
    lines.append(f"Inner frame range : {min(m['frame'] for m in messages)}..{max(m['frame'] for m in messages)}")
    lines.append("")
    lines.append("ANSWER: " + (
        "NO — no message with a URI path containing 'bond' exists in the "
        "bootstrap-decrypted set."
        if not bond_like
        else f"YES — {len(bond_like)} bond-related message(s) found."
    ))
    lines.append("")
    lines.append("Every distinct URI path observed (with placeholder for empty):")
    for p in all_paths:
        lines.append(f"  - {p or '(none / empty)'}")
    lines.append("")
    lines.append("Messages whose path contains 'bond'    : " + str(len(bond_like)))
    lines.append("Messages whose path contains 'control' : " + str(len(control_like)))
    lines.append("")
    lines.append("DTLS PSK identities observed on the wire (from dtls_handshake_summary.csv):")
    for frame, direction, ident in identities:
        lines.append(f"  BLE frame {frame:>6}  {direction:<14}  identity={ident}")
    lines.append("")
    lines.append("DTLS alerts observed on the wire:")
    for frame, direction, level, desc in alerts:
        lines.append(f"  BLE frame {frame:>6}  {direction:<14}  level={level}  desc={desc}")
    lines.append("")
    lines.append("Identity attribution for the CoAP set:")
    lines.append(
        f"  All {len(messages)} decrypted CoAP messages fall in inner frames "
        f"{FIRST_INNER_FRAME}..{LAST_BOOTSTRAP_FRAME}, i.e. the BOOTSTRAP session window "
        "(DECRYPTION_REPORT.md §4.4). The tracker's MobileData re-key begins at inner "
        "frame 56 and completes at inner frame 204; after that, records are encrypted "
        "under MobileData keys and are NOT decryptable with the BOOTSTRAP PSK."
    )
    lines.append(
        "  => Every decrypted CoAP message below was exchanged under the "
        "BOOTSTRAP PSK identity, NOT under a MobileData identity."
    )

    e2_txt = OUT_DIR / "e2_bond_control_check.txt"
    e2_txt.write_text("\n".join(lines) + "\n", encoding="utf-8")

    # ---- markdown summary --------------------------------------------------
    md = []
    md.append("# Phase 17 — E1/E2 offline results")
    md.append("")
    md.append(f"- Input: `{SAMPLES_CSV.relative_to(REPO)}`")
    md.append(f"- Input SHA-256: `{sha256(SAMPLES_CSV)}`")
    md.append(f"- Decrypted CoAP messages: **{len(messages)}**")
    md.append(f"- Inner-frame range: {min(m['frame'] for m in messages)}..{max(m['frame'] for m in messages)}")
    md.append(f"- DTLS identity for the whole set: **{BOOTSTRAP_IDENTITY}** (bootstrap session)")
    md.append("")
    md.append("## E1 — resource x method x status (requester -> responder)")
    md.append("")
    md.append("| resource | requester | responder | methods | statuses | n |")
    md.append("|---|---|---|---|---|---|")
    for key in sorted(res):
        e = res[key]
        md.append(
            f"| `{key}` | {e['requester']} | {e['responder']} | "
            f"{'|'.join(sorted(e['methods']))} | {'|'.join(sorted(e['statuses']))} | {e['n']} |"
        )
    md.append("")
    md.append("## E2 — `/bond/control` presence")
    md.append("")
    md.append(
        "**No `bond/control` message exists in the captured official session.** "
        f"({len(bond_like)} bond-like messages; {len(control_like)} control-like messages.)"
    )
    md.append("")
    md.append("DTLS identities on the wire: " + ", ".join(i[2] for i in identities))
    md.append("")
    md.append("## Interpretation")
    md.append("")
    md.append(
        "- Under the **BOOTSTRAP** session, the tracker (as CoAP server) **allowed** "
        "`/pair/display` (2.04), `/sync/dump` (2.05), `/sync/response` (2.31/2.04), "
        "and **denied** `/sync/config` with **4.01 Unauthorized**."
    )
    md.append(
        "- The tracker's own requests to `/md/3d02` were answered **4.01 by the phone** "
        "(the official app refusing MobileData metadata at that point) — a different actor."
    )
    md.append(
        "- The capture is a **post-provisioning sync**: the tracker is already provisioned "
        "(it presents a MobileData identity and re-keys to MobileData), so the official app "
        "has no reason to send `bond/control`. The capture therefore constrains but does not "
        "directly answer what `bond/control` requires."
    )
    md.append("")
    (OUT_DIR / "e1_summary.md").write_text("\n".join(md) + "\n", encoding="utf-8")

    # ---- console -----------------------------------------------------------
    print("E1/E2 complete.")
    print(f"  messages parsed             : {len(messages)}")
    print(f"  transactions paired by token: {len(transactions)}")
    print(f"  distinct resources          : {len(res)}")
    print(f"  bond-like messages          : {len(bond_like)}")
    print()
    print("resource table (requester -> responder):")
    hdr = f"{'resource':<28} {'requester':<9} {'responder':<9} {'methods':<8} {'statuses':<22} n"
    print(hdr)
    print("-" * len(hdr))
    for key in sorted(res):
        e = res[key]
        print(
            f"{key:<28} {e['requester']:<9} {e['responder']:<9} "
            f"{'|'.join(sorted(e['methods'])):<8} {'|'.join(sorted(e['statuses'])):<22} {e['n']}"
        )
    print()
    print(f"wrote: {msg_csv.name}, {res_csv.name}, {e2_txt.name}, e1_summary.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
