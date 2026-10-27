#!/usr/bin/env python3
"""Analyse Fitbit Inspire 3 BLE -> IPv4/UDP/DTLS captures.

Inputs (all in the working directory):
  successful_sync_exchange.tsv   UTF-16 LE TSV of ATT writes/notifications
  inspire3_inner_traffic.pcap    little-endian PCAP of the reconstructed inner traffic
  successful_sync.log            btsnoop HCI log (optional, for wall-clock timestamps)

Outputs (under inspire3_analysis_output/):
  control_events.csv              0x80 / 0x81 link-control PDUs
  reconstructed_packets.csv       every IPv4 packet rebuilt from the ATT TSV
  pcap_comparison.csv             how the reconstructed packets match/mismatch the PCAP
  missing_packets.csv             packets present in the TSV reconstruction but absent/broken in the PCAP
  dtls_handshake_summary.csv      DTLS handshake records
  timeline.txt                    human-readable chronological narrative
  raw_att_*.bin                   raw ATT payloads with the per-PDU prefix byte
  ip_stream_*.bin                 reassembled IPv4 packet streams
  reconstructed.pcap              PCAP rebuilt from the TSV reconstruction
  frame_times.csv                 frame.number -> frame.time from the btsnoop log

The script uses only the Python standard library.  tshark is required only for
wall-clock timestamps; if it is missing all timestamp fields are left blank.
"""
import argparse
import csv
import datetime
import os
import struct
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
TSHARK_DEFAULT = Path(r"C:\Program Files\Wireshark\tshark.exe")

HS_TYPES = {
    1: "ClientHello",
    2: "ServerHello",
    3: "HelloVerifyRequest",
    11: "Certificate",
    12: "ServerKeyExchange",
    13: "CertificateRequest",
    14: "ServerHelloDone",
    15: "CertificateVerify",
    16: "ClientKeyExchange",
    20: "Finished",
}

CT_TYPES = {
    20: "change_cipher_spec",
    21: "alert",
    22: "handshake",
    23: "application_data",
}

ALERT_DESC = {
    0: "close_notify",
    10: "unexpected_message",
    20: "bad_record_mac",
    50: "decode_error",
    70: "protocol_version",
    71: "insufficient_security",
    115: "unknown_psk_identity",
}

CIPHER_SUITES = {
    0xC0A4: "TLS_PSK_WITH_AES_128_CCM",
    0x00A8: "TLS_PSK_WITH_AES_256_CCM",
    0xC037: "TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256",
    0x00FF: "TLS_EMPTY_RENEGOTIATION_INFO_SCSV",
}

# ---------------------------------------------------------------------------
# ATT TSV handling
# ---------------------------------------------------------------------------
def read_tsv(path: Path):
    """Return a list of dicts, one per TSV row that carries a payload."""
    rows = []
    with path.open("r", encoding="utf-16-le", newline="") as f:
        reader = csv.reader(f, delimiter="\t")
        try:
            next(reader)  # skip header
        except StopIteration:
            return rows
        for raw in reader:
            if len(raw) < 4:
                continue
            frame, opcode, handle, value = raw[0], raw[1], raw[2], raw[3]
            if not value:
                continue
            try:
                value_bytes = bytes.fromhex(value)
            except ValueError:
                continue
            rows.append({
                "frame": int(frame),
                "opcode": opcode,
                "handle": handle,
                "value": value_bytes,
                "prefix": value_bytes[0] if value_bytes else None,
                "payload": value_bytes[1:] if len(value_bytes) > 1 else b"",
            })
    return rows


def split_directions(rows):
    """Split ATT rows into phone->tracker and tracker->phone streams."""
    phone_to_tracker = []
    tracker_to_phone = []
    control = []
    for r in rows:
        if r["opcode"] == "0x52" and r["handle"] == "0x0042":
            phone_to_tracker.append(r)
            if r["prefix"] is not None and r["prefix"] >= 0x80:
                control.append({**r, "direction": "phone->tracker"})
        elif r["opcode"] == "0x1b" and r["handle"] == "0x0044":
            tracker_to_phone.append(r)
            if r["prefix"] is not None and r["prefix"] >= 0x80:
                control.append({**r, "direction": "tracker->phone"})
    return phone_to_tracker, tracker_to_phone, control


# ---------------------------------------------------------------------------
# IPv4 / UDP / DTLS parsing
# ---------------------------------------------------------------------------
def reconstruct_ip_packets(pdus, direction_name):
    """Concatenate data PDUs and slice out IPv4 packets.

    The per-PDU prefix byte is stripped; IPv4 packets are delimited by the
    IPv4 total-length field.  Zero-length PDUs are treated as ACKs and do not
    advance the byte cursor.
    """
    data = bytearray()
    meta = []  # (byte_start, byte_end_excl, frame, prefix)
    for pdu in pdus:
        prefix = pdu["prefix"]
        if prefix is None or prefix >= 0x80:
            continue
        payload = pdu["payload"]
        start = len(data)
        data.extend(payload)
        meta.append((start, len(data), pdu["frame"], prefix, len(payload)))

    packets = []
    pos = 0
    for mstart, mend, frame, prefix, plen in meta:
        if pos < mstart:
            pos = mstart
        if pos >= len(data):
            break
        # Zero-length data PDUs are ACKs; they share the same byte offset
        # as the next packet but must not be reported as the packet start.
        if pos == mstart and plen == 0:
            continue
        if data[pos] != 0x45:
            # Should not happen in a clean capture; report and stop.
            break
        total = struct.unpack_from(">H", data, pos + 2)[0]
        if pos + total > len(data):
            break
        pkt = bytes(data[pos:pos + total])
        packets.append({
            "first_frame": frame,
            "first_prefix": prefix,
            "direction": direction_name,
            "bytes": pkt,
            "src": ".".join(str(b) for b in pkt[12:16]),
            "dst": ".".join(str(b) for b in pkt[16:20]),
            "ip_id": struct.unpack(">H", pkt[4:6])[0],
            "ip_total": total,
        })
        pos += total

    return packets, bytes(data)


def parse_udp(pkt_bytes):
    """Return dict with UDP src/dst ports and payload, or None."""
    ihl = (pkt_bytes[0] & 0x0F) * 4
    if len(pkt_bytes) < ihl + 8:
        return None
    src_port, dst_port, udp_len = struct.unpack(">HHH", pkt_bytes[ihl:ihl + 6])
    payload = pkt_bytes[ihl + 8:]
    return {
        "src_port": src_port,
        "dst_port": dst_port,
        "udp_len": udp_len,
        "payload": payload,
    }


def parse_dtls(udp_payload):
    """Parse all DTLS records in a UDP payload."""
    records = []
    i = 0
    while i + 13 <= len(udp_payload):
        ct = udp_payload[i]
        version = struct.unpack(">H", udp_payload[i + 1:i + 3])[0]
        epoch = struct.unpack(">H", udp_payload[i + 3:i + 5])[0]
        seq = struct.unpack(">Q", b"\x00\x00" + udp_payload[i + 5:i + 11])[0]
        length = struct.unpack(">H", udp_payload[i + 11:i + 13])[0]
        if i + 13 + length > len(udp_payload):
            break
        body = udp_payload[i + 13:i + 13 + length]
        rec = {
            "content_type": ct,
            "content_type_name": CT_TYPES.get(ct, f"0x{ct:02x}"),
            "version": f"0x{version:04x}",
            "epoch": epoch,
            "seq": seq,
            "length": length,
        }
        if ct == 22:
            if len(body) >= 12:
                ht = body[0]
                hlen = struct.unpack(">I", b"\x00" + body[1:4])[0]
                msgseq = struct.unpack(">H", body[4:6])[0]
                fragoff = struct.unpack(">I", b"\x00" + body[6:9])[0]
                fraglen = struct.unpack(">I", b"\x00" + body[9:12])[0]
                rec["handshake"] = {
                    "type": ht,
                    "type_name": HS_TYPES.get(ht, f"0x{ht:02x}"),
                    "length": hlen,
                    "msgseq": msgseq,
                    "fragoff": fragoff,
                    "fraglen": fraglen,
                }
                if ht == 16 and len(body) >= 14:
                    id_len = struct.unpack(">H", body[12:14])[0]
                    rec["psk_identity"] = body[14:14 + id_len].decode(
                        "utf-8", errors="replace"
                    )
                if ht == 1:
                    rec["client_hello"] = parse_client_hello(body)
                if ht == 2:
                    rec["server_hello"] = parse_server_hello(body)
                if epoch == 1 or (ht not in HS_TYPES):
                    rec["note"] = "encrypted_handshake"
        elif ct == 21:
            if len(body) >= 2:
                rec["alert"] = {
                    "level": body[0],
                    "desc": body[1],
                    "desc_name": ALERT_DESC.get(body[1], f"0x{body[1]:02x}"),
                }
        elif ct == 20:
            rec["ccs"] = {"data": body.hex()}
        elif ct == 23:
            rec["note"] = "encrypted_application_data"
        records.append(rec)
        i += 13 + length
    return records


def parse_client_hello(body):
    """Quick parse of a DTLS ClientHello body (after handshake header)."""
    try:
        p = 12
        ver = struct.unpack(">H", body[p:p + 2])[0]
        p += 2 + 32  # version + random
        sid_len = body[p]
        p += 1 + sid_len
        cookie_len = body[p]
        p += 1 + cookie_len
        cs_len = struct.unpack(">H", body[p:p + 2])[0]
        p += 2
        suites = [struct.unpack(">H", body[p + j:p + j + 2])[0]
                  for j in range(0, cs_len, 2)]
        p += cs_len
        comp_len = body[p]
        p += 1 + comp_len
        ext_len = struct.unpack(">H", body[p:p + 2])[0]
        return {
            "version": f"0x{ver:04x}",
            "cookie_len": cookie_len,
            "cipher_suites": [CIPHER_SUITES.get(s, f"0x{s:04x}") for s in suites],
            "extensions_len": ext_len,
        }
    except Exception:
        return {}


def parse_server_hello(body):
    """Quick parse of a DTLS ServerHello body."""
    try:
        p = 12
        ver = struct.unpack(">H", body[p:p + 2])[0]
        p += 2 + 32
        sid_len = body[p]
        p += 1 + sid_len
        cs = struct.unpack(">H", body[p:p + 2])[0]
        p += 2
        comp = body[p]
        p += 1
        ext_len = struct.unpack(">H", body[p:p + 2])[0]
        return {
            "version": f"0x{ver:04x}",
            "cipher_suite": CIPHER_SUITES.get(cs, f"0x{cs:04x}"),
            "compression": comp,
            "extensions_len": ext_len,
        }
    except Exception:
        return {}


# ---------------------------------------------------------------------------
# PCAP helpers
# ---------------------------------------------------------------------------
def read_pcap(path: Path):
    """Read a little-endian PCAP (DLT_RAW) and return list of raw IPv4 packets."""
    with path.open("rb") as f:
        gh = f.read(24)
        if len(gh) < 24:
            raise ValueError("PCAP too short")
        magic, _, _, _, _, snaplen, network = struct.unpack("<IHHiIII", gh)
        if magic != 0xA1B2C3D4:
            raise ValueError(f"Unexpected PCAP magic 0x{magic:08x}")
        packets = []
        while True:
            ph = f.read(16)
            if len(ph) < 16:
                break
            ts_sec, ts_usec, incl_len, orig_len = struct.unpack("<IIII", ph)
            data = f.read(incl_len)
            packets.append({
                "ts_sec": ts_sec,
                "ts_usec": ts_usec,
                "orig_len": orig_len,
                "bytes": data,
            })
    return packets


def write_pcap(path: Path, rec_packets, frame_times):
    """Write a little-endian PCAP of reconstructed IPv4 packets."""
    with path.open("wb") as f:
        f.write(struct.pack("<IHHiIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 101))
        for pkt in rec_packets:
            data = pkt["bytes"]
            frame = pkt["first_frame"]
            ts = frame_times.get(frame)
            if ts is None:
                ts = 0.0
            ts_sec = int(ts)
            ts_usec = int((ts - ts_sec) * 1_000_000)
            f.write(struct.pack("<IIII", ts_sec, ts_usec, len(data), len(data)))
            f.write(data)


# ---------------------------------------------------------------------------
# tshark / btsnoop timestamp extraction
# ---------------------------------------------------------------------------
def load_frame_times(btsnoop: Path, tshark: Path, outdir: Path):
    """Return a dict frame.number -> epoch seconds, using tshark if available."""
    times = {}
    if not btsnoop.exists():
        return times
    if not tshark.exists():
        return times
    out_csv = outdir / "frame_times.csv"
    try:
        proc = subprocess.run(
            [str(tshark), "-r", str(btsnoop), "-T", "fields",
             "-e", "frame.number", "-e", "frame.time"],
            capture_output=True, text=True, check=False, timeout=120,
        )
    except Exception:
        return times
    with out_csv.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["frame.number", "frame.time", "epoch_seconds"])
        for line in proc.stdout.splitlines():
            parts = line.split("\t", 1)
            if len(parts) != 2:
                continue
            try:
                frame = int(parts[0])
            except ValueError:
                continue
            ts_str = parts[1].strip()
            epoch = parse_tshark_time(ts_str)
            times[frame] = epoch
            writer.writerow([frame, ts_str, epoch])
    return times


def parse_tshark_time(ts_str: str):
    """Convert an ISO-like tshark time to epoch seconds."""
    try:
        dt = datetime.datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
        return dt.timestamp()
    except Exception:
        return 0.0


# ---------------------------------------------------------------------------
# Reconstruction vs PCAP comparison
# ---------------------------------------------------------------------------
def pcap_key(pkt):
    b = pkt["bytes"]
    return (bytes(b[12:16]), bytes(b[16:20]), struct.unpack(">H", b[4:6])[0])


def rec_key(pkt):
    return (bytes(pkt["bytes"][12:16]), bytes(pkt["bytes"][16:20]), pkt["ip_id"])


def compare_streams(rec_packets, pcap_packets):
    """Match reconstructed packets to PCAP packets."""
    rec_used = [False] * len(rec_packets)
    results = []
    for pidx, pc in enumerate(pcap_packets):
        key = pcap_key(pc)
        match_i = None
        for ri, rp in enumerate(rec_packets):
            if rec_used[ri]:
                continue
            if rec_key(rp) != key:
                continue
            # PCAP is either identical to, or a prefix of, the reconstructed packet.
            if rp["bytes"].startswith(pc["bytes"]):
                match_i = ri
                break
        if match_i is None:
            results.append({
                "pcap_frame": pidx + 1,
                "pcap_len": len(pc["bytes"]),
                "rec_frame": "",
                "direction": "",
                "ip_src": ".".join(str(b) for b in pc["bytes"][12:16]),
                "ip_dst": ".".join(str(b) for b in pc["bytes"][16:20]),
                "ip_id": struct.unpack(">H", pc["bytes"][4:6])[0],
                "rec_len": "",
                "status": "unmatched_pcap_frame",
                "note": "frame in PCAP does not match any reconstructed IPv4 packet",
            })
        else:
            rec_used[match_i] = True
            rp = rec_packets[match_i]
            exact = rp["bytes"] == pc["bytes"]
            results.append({
                "pcap_frame": pidx + 1,
                "pcap_len": len(pc["bytes"]),
                "rec_frame": rp["first_frame"],
                "direction": rp["direction"],
                "ip_src": rp["src"],
                "ip_dst": rp["dst"],
                "ip_id": rp["ip_id"],
                "rec_len": len(rp["bytes"]),
                "status": "exact" if exact else "truncated",
                "note": "" if exact else "PCAP frame is a truncated prefix of the reconstructed packet",
            })

    missing = []
    for ri, rp in enumerate(rec_packets):
        if rec_used[ri]:
            continue
        missing.append({
            "rec_frame": rp["first_frame"],
            "direction": rp["direction"],
            "ip_src": rp["src"],
            "ip_dst": rp["dst"],
            "ip_id": rp["ip_id"],
            "rec_len": len(rp["bytes"]),
            "status": "missing_in_pcap",
            "note": "reconstructed packet is absent from the PCAP (fragmentation/reassembly error)",
        })
    return results, missing


# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------
def write_csv(path: Path, fieldnames, rows):
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def fmt_records(records):
    """One-line summary of DTLS records."""
    parts = []
    for r in records:
        s = r["content_type_name"]
        if "handshake" in r:
            s += f"/{r['handshake']['type_name']}"
        if "psk_identity" in r:
            s += f"(psk={r['psk_identity']})"
        if "alert" in r:
            s += f"(alert={r['alert']['desc_name']})"
        parts.append(s)
    return "; ".join(parts)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description="Analyse Fitbit Inspire 3 captures")
    parser.add_argument("--tsv", type=Path, default=Path("successful_sync_exchange.tsv"))
    parser.add_argument("--pcap", type=Path, default=Path("inspire3_inner_traffic.pcap"))
    parser.add_argument("--btsnoop", type=Path, default=Path("successful_sync.log"))
    parser.add_argument("--tshark", type=Path, default=TSHARK_DEFAULT)
    parser.add_argument("--outdir", type=Path, default=Path("inspire3_analysis_output"))
    args = parser.parse_args()

    if not args.tsv.is_absolute():
        args.tsv = Path.cwd() / args.tsv
    if not args.pcap.is_absolute():
        args.pcap = Path.cwd() / args.pcap
    if not args.btsnoop.is_absolute():
        args.btsnoop = Path.cwd() / args.btsnoop

    if not args.tsv.exists():
        print(f"ERROR: TSV not found: {args.tsv}", file=sys.stderr)
        sys.exit(1)

    outdir = args.outdir
    outdir.mkdir(parents=True, exist_ok=True)

    print("[*] Reading ATT exchange TSV ...")
    rows = read_tsv(args.tsv)
    phone_pdus, tracker_pdus, control = split_directions(rows)
    print(f"    phone->tracker PDUs: {len(phone_pdus)}")
    print(f"    tracker->phone PDUs: {len(tracker_pdus)}")
    print(f"    control PDUs:        {len(control)}")

    # Raw ATT byte streams (prefix byte included)
    (outdir / "raw_att_phone_to_tracker.bin").write_bytes(
        b"".join(r["value"] for r in phone_pdus)
    )
    (outdir / "raw_att_tracker_to_phone.bin").write_bytes(
        b"".join(r["value"] for r in tracker_pdus)
    )

    # Control events CSV
    write_csv(
        outdir / "control_events.csv",
        ["frame", "direction", "prefix_hex", "payload_hex", "note"],
        [{
            "frame": c["frame"],
            "direction": c["direction"],
            "prefix_hex": f"0x{c['prefix']:02x}",
            "payload_hex": c["payload"].hex(),
            "note": "MTU=2056" if c["prefix"] == 0x81 and c["payload"] == b"\x00\x00\x08\x08" else ("link-control" if c["prefix"] == 0x80 else ""),
        } for c in control]
    )

    print("[*] Reconstructing IPv4 packet streams ...")
    phone_packets, phone_stream = reconstruct_ip_packets(phone_pdus, "phone->tracker")
    tracker_packets, tracker_stream = reconstruct_ip_packets(tracker_pdus, "tracker->phone")
    (outdir / "ip_stream_phone_to_tracker.bin").write_bytes(phone_stream)
    (outdir / "ip_stream_tracker_to_phone.bin").write_bytes(tracker_stream)
    print(f"    phone->tracker IPv4 packets: {len(phone_packets)}")
    print(f"    tracker->phone IPv4 packets: {len(tracker_packets)}")

    all_rec = sorted(phone_packets + tracker_packets, key=lambda x: x["first_frame"])

    # DTLS parsing and enrichment
    for pkt in all_rec:
        udp = parse_udp(pkt["bytes"])
        pkt["udp"] = udp
        if udp:
            pkt["dtls_records"] = parse_dtls(udp["payload"])
        else:
            pkt["dtls_records"] = []

    # Frame times from btsnoop
    print("[*] Extracting wall-clock timestamps (requires tshark + successful_sync.log) ...")
    frame_times = load_frame_times(args.btsnoop, args.tshark, outdir)
    print(f"    resolved {len(frame_times)} frame timestamps")

    # Reconstructed packets CSV
    rec_rows = []
    for pkt in all_rec:
        udp = pkt.get("udp")
        dtls = pkt.get("dtls_records", [])
        rec_rows.append({
            "first_ble_frame": pkt["first_frame"],
            "timestamp": frame_times.get(pkt["first_frame"], ""),
            "direction": pkt["direction"],
            "ip_src": pkt["src"],
            "ip_dst": pkt["dst"],
            "ip_id": pkt["ip_id"],
            "ip_total_length": pkt["ip_total"],
            "udp_src_port": udp["src_port"] if udp else "",
            "udp_dst_port": udp["dst_port"] if udp else "",
            "dtls_records": fmt_records(dtls),
        })
    write_csv(
        outdir / "reconstructed_packets.csv",
        ["first_ble_frame", "timestamp", "direction", "ip_src", "ip_dst", "ip_id",
         "ip_total_length", "udp_src_port", "udp_dst_port", "dtls_records"],
        rec_rows,
    )

    # DTLS handshake / alert summary
    hs_rows = []
    for idx, pkt in enumerate(all_rec):
        for r in pkt.get("dtls_records", []):
            hs_rows.append({
                "ble_frame": pkt["first_frame"],
                "timestamp": frame_times.get(pkt["first_frame"], ""),
                "direction": pkt["direction"],
                "ip_id": pkt["ip_id"],
                "content_type": r["content_type_name"],
                "epoch": r["epoch"],
                "dtls_seq": r["seq"],
                "handshake_type": r.get("handshake", {}).get("type_name", ""),
                "psk_identity": r.get("psk_identity", ""),
                "alert_level": r.get("alert", {}).get("level", ""),
                "alert_desc": r.get("alert", {}).get("desc_name", ""),
                "dtls_note": r.get("note", ""),
                "cipher_suite": r.get("server_hello", {}).get("cipher_suite", "")
                                or ", ".join(r.get("client_hello", {}).get("cipher_suites", [])),
            })
    write_csv(
        outdir / "dtls_handshake_summary.csv",
        ["ble_frame", "timestamp", "direction", "ip_id", "content_type", "epoch",
         "dtls_seq", "handshake_type", "psk_identity", "alert_level", "alert_desc",
         "cipher_suite", "dtls_note"],
        hs_rows,
    )

    # Compare with original PCAP
    print("[*] Comparing reconstruction with original PCAP ...")
    pcap_packets = read_pcap(args.pcap)
    comparison, missing = compare_streams(all_rec, pcap_packets)
    exact = sum(1 for c in comparison if c["status"] == "exact")
    truncated = sum(1 for c in comparison if c["status"] == "truncated")
    unmatched = sum(1 for c in comparison if c["status"] == "unmatched_pcap_frame")
    print(f"    exact: {exact}  truncated/malformed: {truncated}  unmatched: {unmatched}  missing: {len(missing)}")

    # Merge status into rec_rows for convenience
    status_by_frame = {c["rec_frame"]: c["status"] for c in comparison if c["rec_frame"] != ""}
    for r in rec_rows:
        r["pcap_status"] = status_by_frame.get(r["first_ble_frame"], "missing_in_pcap")
    write_csv(
        outdir / "reconstructed_packets.csv",
        ["first_ble_frame", "timestamp", "direction", "ip_src", "ip_dst", "ip_id",
         "ip_total_length", "udp_src_port", "udp_dst_port", "pcap_status", "dtls_records"],
        rec_rows,
    )

    write_csv(
        outdir / "pcap_comparison.csv",
        ["pcap_frame", "pcap_len", "rec_frame", "direction", "ip_src", "ip_dst",
         "ip_id", "rec_len", "status", "note"],
        comparison,
    )
    write_csv(
        outdir / "missing_packets.csv",
        ["rec_frame", "direction", "ip_src", "ip_dst", "ip_id", "rec_len", "status", "note"],
        missing,
    )

    # Reconstructed PCAP
    print("[*] Writing reconstructed PCAP ...")
    write_pcap(outdir / "reconstructed.pcap", all_rec, frame_times)

    # Timeline narrative
    print("[*] Writing timeline narrative ...")
    with (outdir / "timeline.txt").open("w", encoding="utf-8") as f:
        f.write("Fitbit Inspire 3 – reconstructed IPv4/DTLS timeline\n")
        f.write("=" * 70 + "\n\n")
        f.write(f"Total ATT PDUs parsed: {len(rows)}\n")
        f.write(f"phone->tracker IPv4 packets: {len(phone_packets)}\n")
        f.write(f"tracker->phone IPv4 packets: {len(tracker_packets)}\n")
        f.write(f"PCAP comparison: exact={exact} truncated={truncated} unmatched={unmatched} missing={len(missing)}\n\n")

        f.write("Link-control events:\n")
        for c in control:
            ts = frame_times.get(c["frame"], "")
            note = ""
            if c["prefix"] == 0x80:
                note = "link-control (empty)"
            elif c["prefix"] == 0x81:
                note = f"MTU advertise {int.from_bytes(c['payload'], 'big')}"
            f.write(f"  frame {c['frame']} {c['direction']} 0x{c['prefix']:02x} {note}\n")
        f.write("\n")

        f.write("Key DTLS events (chronological by BLE frame):\n")
        for pkt in all_rec:
            for r in pkt.get("dtls_records", []):
                ts = frame_times.get(pkt["first_frame"], "")
                line = f"  frame {pkt['first_frame']} ({ts}) {pkt['direction']} ipid={pkt['ip_id']} {r['content_type_name']}"
                if "handshake" in r:
                    line += f" {r['handshake']['type_name']}"
                if "psk_identity" in r:
                    line += f" psk_identity={r['psk_identity']}"
                if "alert" in r:
                    line += f" alert_level={r['alert']['level']} alert_desc={r['alert']['desc_name']}"
                if "note" in r:
                    line += f" [{r['note']}]"
                f.write(line + "\n")

        f.write("\nFirst encrypted application-data records:\n")
        for pkt in all_rec:
            for r in pkt.get("dtls_records", []):
                if r["content_type"] == 23:
                    f.write(f"  frame {pkt['first_frame']} {pkt['direction']} ipid={pkt['ip_id']} "
                            f"epoch={r['epoch']} seq={r['seq']}\n")
                    break
            else:
                continue
            break

    print(f"[+] Done. Outputs written to {outdir}")


if __name__ == "__main__":
    main()
