#!/usr/bin/env python3
"""Validation of reconstructed DTLS traffic for BlueBit Inspire 3 analysis."""
import csv
import struct
from pathlib import Path

OUTDIR = Path(r"G:\Dev\BlueBit\inspire3_decryption_analysis\reconstruction_outputs")
TSV = Path(r"G:\Dev\BlueBit\successful_sync_exchange.tsv")
LOG = Path(r"G:\Dev\BlueBit\successful_sync.log")
PCAP = Path(r"G:\Dev\BlueBit\inspire3_inner_traffic.pcap")
REPORT = Path(r"G:\Dev\BlueBit\inspire3_protocol_analysis_report.md")

def read_csv(path):
    with open(path, "r", encoding="utf-8") as f:
        return list(csv.DictReader(f))

def read_tsv(path):
    rows = []
    with open(path, "rb") as f:
        raw = f.read()
    text = raw.decode("utf-16-le")
    lines = text.splitlines()
    # strip BOM and whitespace from header keys
    header = [h.lstrip("\ufeff").strip() for h in lines[0].split("\t")]
    reader = csv.DictReader(lines[1:], fieldnames=header, delimiter="\t")
    for r in reader:
        try:
            val = r.get("btatt.value", "")
            if not val:
                continue
            payload = bytes.fromhex(val)
        except Exception:
            continue
        rows.append({
            "frame": int(r["frame.number"].strip()),
            "opcode": r.get("btatt.opcode", "").strip(),
            "handle": r.get("btatt.handle", "").strip(),
            "payload": payload,
            "prefix": payload[0] if payload else None,
        })
    return rows

def split_directions(rows):
    p2t, t2p = [], []
    for r in rows:
        if r["opcode"] == "0x52" and r["handle"] == "0x0042":
            p2t.append(r)
        elif r["opcode"] == "0x1b" and r["handle"] == "0x0044":
            t2p.append(r)
    return p2t, t2p

def reconstruct_with_meta(pdus):
    """Return (packets, meta) where meta maps packet index to list of (frame, plen)."""
    data = bytearray()
    meta = []  # (byte_start, byte_end_excl, frame, prefix, plen)
    for pdu in pdus:
        prefix = pdu["prefix"]
        if prefix is None or prefix >= 0x80:
            continue
        payload = pdu["payload"]
        start = len(data)
        data.extend(payload[1:] if payload else b"")
        meta.append((start, len(data), pdu["frame"], prefix, len(payload) - 1 if payload else 0))

    packets = []
    pkt_meta = []
    pos = 0
    for idx, (mstart, mend, frame, prefix, plen) in enumerate(meta):
        if pos < mstart:
            pos = mstart
        if pos >= len(data):
            break
        if pos == mstart and plen == 0:
            continue
        if data[pos] != 0x45:
            break
        total = struct.unpack_from(">H", data, pos + 2)[0]
        if pos + total > len(data):
            break
        pkt = bytes(data[pos:pos + total])
        # gather all meta entries that contributed bytes to this packet
        contrib = []
        p = pos
        for mm in meta:
            ms, me, mf, mp, mpl = mm
            if ms >= pos + total:
                break
            if me <= pos:
                continue
            contrib.append((mf, mpl))
        packets.append(pkt)
        pkt_meta.append(contrib)
        pos += total
    return packets, pkt_meta

def parse_udp_payload(pkt_bytes):
    """Return UDP payload from IPv4 packet."""
    ihl = (pkt_bytes[0] & 0x0F) * 4
    if len(pkt_bytes) < ihl + 8:
        return None
    udp_len = struct.unpack(">H", pkt_bytes[ihl + 4:ihl + 6])[0]
    return pkt_bytes[ihl + 8:ihl + udp_len]


def parse_dtls_records(payload):
    """Yield DTLS record dicts from UDP payload."""
    i = 0
    while i + 13 <= len(payload):
        ct = payload[i]
        version = struct.unpack(">H", payload[i + 1:i + 3])[0]
        epoch = struct.unpack(">H", payload[i + 3:i + 5])[0]
        seq = struct.unpack(">Q", b"\x00\x00" + payload[i + 5:i + 11])[0]
        length = struct.unpack(">H", payload[i + 11:i + 13])[0]
        if i + 13 + length > len(payload):
            break
        body = payload[i + 13:i + 13 + length]
        yield {
            "content_type": ct,
            "version": version,
            "epoch": epoch,
            "seq": seq,
            "length": length,
            "body": body,
        }
        i += 13 + length


def parse_client_hello_random(body):
    """Extract random from DTLS ClientHello handshake body (after handshake hdr)."""
    if len(body) < 12 + 2 + 32:
        return None
    hs_body = body[12:]
    if len(hs_body) < 34:
        return None
    return hs_body[2:34].hex()


def parse_server_hello_random(body):
    """Extract random from DTLS ServerHello handshake body (after handshake hdr)."""
    if len(body) < 12 + 2 + 32:
        return None
    hs_body = body[12:]
    if len(hs_body) < 34:
        return None
    return hs_body[2:34].hex()


def main():
    print("=" * 70)
    print("RECONSTRUCTION VALIDATION")
    print("=" * 70)

    rec = read_csv(OUTDIR / "reconstructed_packets.csv")
    miss = read_csv(OUTDIR / "missing_packets.csv")
    comp = read_csv(OUTDIR / "pcap_comparison.csv")

    print("\n1. reconstructed_packets.csv counts")
    print(f"   total rows: {len(rec)}")
    p2t = [r for r in rec if r["direction"] == "phone->tracker"]
    t2p = [r for r in rec if r["direction"] == "tracker->phone"]
    print(f"   phone->tracker: {len(p2t)}")
    print(f"   tracker->phone: {len(t2p)}")

    print("\n2. missing_packets.csv count")
    print(f"   total rows: {len(miss)}")

    print("\n3. pcap_comparison.csv status counts")
    from collections import Counter
    c = Counter(r["status"] for r in comp)
    for k, v in sorted(c.items()):
        print(f"   {k}: {v}")

    trunc = [r for r in comp if r["status"] == "truncated"]
    print(f"\n4. Truncated/malformed frames: {len(trunc)}")
    by_dir = Counter(r["direction"] for r in trunc)
    print(f"   by direction: {dict(by_dir)}")
    print("\n   frame  pcap_len  rec_len  direction                ip_id  rec_total_len")
    for r in trunc:
        print(f"   {r['pcap_frame']:>5}  {r['pcap_len']:>7}  {r['rec_len']:>7}  {r['direction']:<22} {r['ip_id']:>5} {r['rec_len']:>10}")

    print("\n5. Sample non-truncated frames: IPv4 total-length vs ATT payload sum")
    rows_tsv = read_tsv(TSV)
    p2t_pdus, t2p_pdus = split_directions(rows_tsv)
    p2t_pkts, p2t_meta = reconstruct_with_meta(p2t_pdus)
    t2p_pkts, t2p_meta = reconstruct_with_meta(t2p_pdus)

    # Build frame -> packet index lookup
    def find_pkt_idx(pkts, meta, frame):
        for j, _ in enumerate(pkts):
            if meta[j] and meta[j][0][0] == frame:
                return j
        return None

    # sample: 5 exact handshake + 5 exact application-data frames
    sample_frames = []
    for r in rec:
        if r["pcap_status"] != "exact":
            continue
        if len(sample_frames) >= 10:
            break
        sample_frames.append(r)

    print(f"   selected {len(sample_frames)} exact frames")
    print("   first_ble_frame  direction           ip_total_len  att_sum  match")
    all_match = True
    for r in sample_frames:
        direction = r["direction"]
        frame = int(r["first_ble_frame"])
        ip_total = int(r["ip_total_length"])
        pkts, pmeta = (p2t_pkts, p2t_meta) if direction == "phone->tracker" else (t2p_pkts, t2p_meta)
        pkt_idx = find_pkt_idx(pkts, pmeta, frame)
        if pkt_idx is None:
            print(f"   {frame:<16} {direction:<20} {ip_total:<13} ?        not found")
            all_match = False
            continue
        att_sum = sum(plen for _, plen in pmeta[pkt_idx])
        ok = att_sum == ip_total
        print(f"   {frame:<16} {direction:<20} {ip_total:<13} {att_sum:<8} {'YES' if ok else 'NO'}")
        if not ok:
            all_match = False
    print(f"   all match: {'YES' if all_match else 'NO'}")

    print("\n6. Bootstrap handshake parameters")
    hs = read_csv(OUTDIR / "dtls_handshake_summary.csv")
    bootstrap_frames = (12221, 12228, 12241, 12247)
    for r in hs:
        if int(r["ble_frame"]) in bootstrap_frames:
            print(f"   frame {r['ble_frame']} {r['direction']:<22} {r['content_type']}/{r.get('handshake_type','')}")

    # Extract randoms from reconstructed packets
    client_random = None
    server_random = None
    for r in rec:
        frame = int(r["first_ble_frame"])
        direction = r["direction"]
        pkts, pmeta = (p2t_pkts, p2t_meta) if direction == "phone->tracker" else (t2p_pkts, t2p_meta)
        pkt_idx = find_pkt_idx(pkts, pmeta, frame)
        if pkt_idx is None:
            continue
        pkt = pkts[pkt_idx]
        payload = parse_udp_payload(pkt)
        if not payload:
            continue
        for rec_dtls in parse_dtls_records(payload):
            body = rec_dtls["body"]
            if len(body) < 12:
                continue
            hs_type = body[0]
            if hs_type == 1 and frame == 12221:  # ClientHello
                client_random = parse_client_hello_random(body)
            elif hs_type == 2 and frame == 12228:  # ServerHello
                server_random = parse_server_hello_random(body)

    print(f"\n   Client random (frame 12221): {client_random}")
    print(f"   Server random (frame 12228): {server_random}")
    print("   PSK identity used: BOOTSTRAP")

    print("\n   First application-data records after bootstrap handshake:")
    app = [r for r in hs if r["content_type"] == "application_data"]
    for r in app[:6]:
        print(f"     frame {r['ble_frame']} {r['direction']:<22} epoch={r['epoch']} seq={r['dtls_seq']}")

    print("\n" + "=" * 70)
    print("VALIDATION COMPLETE")
    print("=" * 70)

if __name__ == "__main__":
    main()
