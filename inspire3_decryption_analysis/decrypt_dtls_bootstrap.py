#!/usr/bin/env python3
"""Decrypt Fitbit Inspire 3 DTLS bootstrap application-data records offline.

Cipher suite: TLS_PSK_WITH_AES_128_CCM (0xC0A4)
PSK identity: BOOTSTRAP
PSK secret  : 81 06 54 E3 36 AD CA B0 A0 3C 60 F7 4A A0 B6 FB
"""

import csv
import hashlib
import hmac
import struct
import sys
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESCCM

# ---------------------------------------------------------------------------
# Paths and constants
# ---------------------------------------------------------------------------
ROOT = Path(r"G:\Dev\BlueBit")
PCAP_PATH = ROOT / "inspire3_analysis_output" / "reconstructed.pcap"
OUTDIR = ROOT / "inspire3_decryption_analysis"
OUTDIR.mkdir(parents=True, exist_ok=True)

BOOTSTRAP_PSK = bytes([
    0x81, 0x06, 0x54, 0xE3, 0x36, 0xAD, 0xCA, 0xB0,
    0xA0, 0x3C, 0x60, 0xF7, 0x4A, 0xA0, 0xB6, 0xFB,
])

CLIENT_IP = bytes([169, 254, 0, 3])  # tracker
SERVER_IP = bytes([169, 254, 0, 2])  # phone

# ---------------------------------------------------------------------------
# TLS/DTLS helpers
# ---------------------------------------------------------------------------
def p_hash(secret: bytes, seed: bytes, length: int, hash_fn) -> bytes:
    """TLS PRF p_hash function."""
    out = b""
    a = hmac.new(secret, seed, hash_fn).digest()
    while len(out) < length:
        out += hmac.new(secret, a + seed, hash_fn).digest()
        a = hmac.new(secret, a, hash_fn).digest()
    return out[:length]


def prf(secret: bytes, label: bytes, seed: bytes, length: int) -> bytes:
    """TLS 1.2 PRF using SHA-256."""
    return p_hash(secret, label + seed, length, hashlib.sha256)


def read_pcap(path: Path):
    """Read a little-endian PCAP (DLT_RAW=101) and return raw frames."""
    data = path.read_bytes()
    magic = data[:4]
    if magic != b"\xd4\xc3\xb2\xa1":
        raise ValueError(f"Not a little-endian PCAP: {path}")
    frames = []
    off = 24
    while off < len(data):
        incl_len = struct.unpack("<I", data[off + 8 : off + 12])[0]
        frames.append(data[off + 16 : off + 16 + incl_len])
        off += 16 + incl_len
    return frames


def parse_ipv4_udp(pkt: bytes):
    """Return (src_ip, dst_ip, ip_id, udp_payload) or None."""
    if len(pkt) < 28:
        return None
    ihl = (pkt[0] & 0x0F) * 4
    src_ip = pkt[12:16]
    dst_ip = pkt[16:20]
    ip_id = struct.unpack(">H", pkt[4:6])[0]
    udp_off = ihl
    if len(pkt) < udp_off + 8:
        return None
    udp_len = struct.unpack(">H", pkt[udp_off + 4 : udp_off + 6])[0]
    payload = pkt[udp_off + 8 : udp_off + udp_len]
    return src_ip, dst_ip, ip_id, payload


def parse_dtls_records(payload: bytes):
    """Yield DTLS records from a UDP payload."""
    off = 0
    while off + 13 <= len(payload):
        content_type = payload[off]
        version = payload[off + 1 : off + 3]
        epoch = struct.unpack(">H", payload[off + 3 : off + 5])[0]
        seq_num = int.from_bytes(payload[off + 5 : off + 11], "big")
        length = struct.unpack(">H", payload[off + 11 : off + 13])[0]
        if off + 13 + length > len(payload):
            break
        fragment = payload[off + 13 : off + 13 + length]
        yield {
            "content_type": content_type,
            "version": version,
            "epoch": epoch,
            "seq_num": seq_num,
            "length": length,
            "fragment": fragment,
            "raw": payload[off : off + 13 + length],
        }
        off += 13 + length


def parse_dtls_handshake_messages(fragment: bytes):
    """Yield (msg_type, body) tuples from a DTLS handshake record fragment."""
    off = 0
    while off + 12 <= len(fragment):
        msg_type = fragment[off]
        length = int.from_bytes(fragment[off + 1 : off + 4], "big")
        # message_seq = struct.unpack(">H", fragment[off+4:off+6])[0]
        frag_offset = int.from_bytes(fragment[off + 6 : off + 9], "big")
        frag_length = int.from_bytes(fragment[off + 9 : off + 12], "big")
        if frag_offset != 0:
            # fragmented; skip for our purposes
            break
        if off + 12 + frag_length > len(fragment):
            break
        body = fragment[off + 12 : off + 12 + frag_length]
        yield (msg_type, body)
        off += 12 + frag_length


def parse_client_hello(body: bytes):
    """Extract client_random from a ClientHello body."""
    if len(body) < 34:
        return None
    return body[2:34]


def parse_server_hello(body: bytes):
    """Extract server_random from a ServerHello body."""
    if len(body) < 34:
        return None
    return body[2:34]


def parse_client_key_exchange_identity(body: bytes):
    """Return PSK identity string from a ClientKeyExchange body."""
    if len(body) < 2:
        return None
    id_len = struct.unpack(">H", body[:2])[0]
    if 2 + id_len > len(body):
        return None
    return body[2 : 2 + id_len].decode("utf-8", errors="replace")


def compute_session_hash(frames: list, client_ip: bytes, server_ip: bytes):
    """Compute RFC 7627 extended-master-secret session hash for bootstrap handshake.

    Tracks handshake boundaries by ClientHello.  Only unencrypted epoch-0
    handshake fragments are included; encrypted Finished records live in
    epoch >= 1 and are excluded.  The bootstrap handshake is the one whose
    ClientKeyExchange carries the PSK identity "BOOTSTRAP".
    """
    current_transcript = b""
    bootstrap_transcript = None

    for idx, pkt in enumerate(frames):
        parsed = parse_ipv4_udp(pkt)
        if parsed is None:
            continue
        src_ip, dst_ip, ip_id, udp_payload = parsed
        for rec in parse_dtls_records(udp_payload):
            if rec["content_type"] != 0x16 or rec["epoch"] != 0:
                continue
            fragment = rec["fragment"]
            off = 0
            while off + 12 <= len(fragment):
                msg_type = fragment[off]
                msg_len = int.from_bytes(fragment[off + 1 : off + 4], "big")
                frag_offset = int.from_bytes(fragment[off + 6 : off + 9], "big")
                frag_len = int.from_bytes(fragment[off + 9 : off + 12], "big")
                if frag_offset != 0 or off + 12 + frag_len > len(fragment):
                    break
                body = fragment[off + 12 : off + 12 + frag_len]

                # A ClientHello from the client starts a new handshake attempt.
                if msg_type == 0x01 and src_ip == client_ip:
                    current_transcript = b""

                current_transcript += fragment[off : off + 12 + frag_len]

                if msg_type == 0x10 and src_ip == client_ip:
                    id_len = struct.unpack(">H", body[:2])[0] if len(body) >= 2 else 0
                    identity = body[2 : 2 + id_len].decode("utf-8", errors="replace")
                    if identity == "BOOTSTRAP":
                        bootstrap_transcript = bytes(current_transcript)

                off += 12 + frag_len

    if bootstrap_transcript is None:
        return None
    return hashlib.sha256(bootstrap_transcript).digest()


def derive_keys(psk: bytes, client_random: bytes, server_random: bytes, session_hash: bytes):
    """Derive master secret and key block for TLS_PSK_WITH_AES_128_CCM.

    Uses the RFC 7627 extended master secret, because the bootstrap handshake
    negotiates that extension.
    """
    # RFC 4279: pre_master_secret = len(other_secret)||other_secret||len(psk)||psk
    # For pure PSK, other_secret is a zero string of the same length as the PSK.
    other_secret = b"\x00" * len(psk)
    pre_master_secret = (
        struct.pack(">H", len(other_secret)) + other_secret
        + struct.pack(">H", len(psk)) + psk
    )

    # RFC 7627 extended master secret (required for this handshake)
    master_secret = prf(
        pre_master_secret,
        b"extended master secret",
        session_hash,
        48,
    )

    # key block: client_write_MAC(0) + server_write_MAC(0) + client_write_key(16)
    #            + server_write_key(16) + client_write_IV(4) + server_write_IV(4)
    key_block = prf(
        master_secret,
        b"key expansion",
        server_random + client_random,
        40,
    )

    client_write_key = key_block[0:16]
    server_write_key = key_block[16:32]
    client_write_iv = key_block[32:36]
    server_write_iv = key_block[36:40]

    return {
        "master_secret": master_secret,
        "client_write_key": client_write_key,
        "server_write_key": server_write_key,
        "client_write_iv": client_write_iv,
        "server_write_iv": server_write_iv,
    }


def decrypt_aead(record: dict, key: bytes, iv: bytes, tag_len: int = 16):
    """Try to decrypt a DTLS 1.2 AES-CCM record. Return plaintext or None."""
    fragment = record["fragment"]
    if len(fragment) < 8 + tag_len:
        return None

    # DTLS 1.2: nonce_explicit is the 64-bit record sequence number (epoch||seq).
    nonce_explicit = fragment[:8]
    ciphertext = fragment[8:]
    nonce = iv + nonce_explicit

    # AAD = seq_num (8 bytes) || type || version || plaintext_length
    seq_num = record["epoch"].to_bytes(2, "big") + record["seq_num"].to_bytes(6, "big")
    plaintext_len = len(ciphertext) - tag_len
    aad = (
        seq_num
        + struct.pack("B", record["content_type"])
        + record["version"]
        + struct.pack(">H", plaintext_len)
    )

    try:
        aesccm = AESCCM(key, tag_length=tag_len)
        plaintext = aesccm.decrypt(nonce, ciphertext, aad)
        return plaintext
    except Exception:
        return None


def is_coap(msg: bytes):
    """Return True if bytes look like a CoAP header."""
    if len(msg) < 4:
        return False
    ver_tkl = msg[0]
    ver = (ver_tkl >> 6) & 0x03
    tkl = ver_tkl & 0x0F
    return ver == 1 and tkl <= 8 and len(msg) >= 4 + tkl


def coap_header_summary(msg: bytes):
    """Return a human-readable CoAP header summary."""
    if len(msg) < 4:
        return "too short"
    ver_tkl = msg[0]
    ver = (ver_tkl >> 6) & 0x03
    tkl = ver_tkl & 0x0F
    code = msg[1]
    code_class = (code >> 5) & 0x07
    code_detail = code & 0x1F
    msg_id = struct.unpack(">H", msg[2:4])[0]
    token = msg[4 : 4 + tkl].hex() if tkl else ""
    return f"ver={ver} tkl={tkl} code={code_class}.{code_detail:02d} mid=0x{msg_id:04x} token={token}"


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    frames = read_pcap(PCAP_PATH)
    print(f"[+] Read {len(frames)} frames from {PCAP_PATH}")

    # Walk frames in order. Track the most recent ClientHello/ServerHello;
    # lock them in when we see a BOOTSTRAP ClientKeyExchange.
    latest_client_random = None
    latest_server_random = None
    bootstrap_randoms = None

    for idx, pkt in enumerate(frames):
        parsed = parse_ipv4_udp(pkt)
        if parsed is None:
            continue
        src_ip, dst_ip, ip_id, udp_payload = parsed
        for rec in parse_dtls_records(udp_payload):
            if rec["content_type"] != 0x16:  # handshake
                continue
            for msg_type, body in parse_dtls_handshake_messages(rec["fragment"]):
                if msg_type == 0x01 and src_ip == CLIENT_IP:
                    cr = parse_client_hello(body)
                    if cr:
                        latest_client_random = cr
                        print(f"[+] ClientHello at frame {idx+1}; client_random={cr.hex()}")
                elif msg_type == 0x02 and src_ip == SERVER_IP:
                    sr = parse_server_hello(body)
                    if sr:
                        latest_server_random = sr
                        print(f"[+] ServerHello at frame {idx+1}; server_random={sr.hex()}")
                elif msg_type == 0x10 and src_ip == CLIENT_IP:
                    identity = parse_client_key_exchange_identity(body)
                    print(f"[+] ClientKeyExchange at frame {idx+1} identity='{identity}'")
                    if identity == "BOOTSTRAP":
                        bootstrap_randoms = (latest_client_random, latest_server_random)
                        print(f"[+] Locked bootstrap randoms: client={latest_client_random.hex() if latest_client_random else None} server={latest_server_random.hex() if latest_server_random else None}")

    if bootstrap_randoms is None or bootstrap_randoms[0] is None or bootstrap_randoms[1] is None:
        print("[-] Could not find complete bootstrap handshake randoms")
        sys.exit(1)

    client_random, server_random = bootstrap_randoms
    session_hash = compute_session_hash(frames, CLIENT_IP, SERVER_IP)
    if session_hash is None:
        print("[-] Could not compute bootstrap session hash")
        sys.exit(1)

    print(f"[+] Bootstrap client_random: {client_random.hex()}")
    print(f"[+] Bootstrap server_random: {server_random.hex()}")
    print(f"[+] Bootstrap session_hash:  {session_hash.hex()}")

    keys = derive_keys(BOOTSTRAP_PSK, client_random, server_random, session_hash)
    print(f"[+] master_secret: {keys['master_secret'].hex()}")
    print(f"[+] client_write_key: {keys['client_write_key'].hex()}")
    print(f"[+] server_write_key: {keys['server_write_key'].hex()}")
    print(f"[+] client_write_iv:  {keys['client_write_iv'].hex()}")
    print(f"[+] server_write_iv:  {keys['server_write_iv'].hex()}")

    # Decrypt application-data records
    decrypted_rows = []
    plaintext_samples = []
    total_app = 0
    verified = 0

    for idx, pkt in enumerate(frames):
        frame_num = idx + 1  # report 1-indexed frame numbers like Wireshark/tshark
        parsed = parse_ipv4_udp(pkt)
        if parsed is None:
            continue
        src_ip, dst_ip, ip_id, udp_payload = parsed
        for rec in parse_dtls_records(udp_payload):
            if rec["content_type"] != 0x17:  # application_data
                continue
            total_app += 1
            is_client = (src_ip == CLIENT_IP)
            key = keys["client_write_key"] if is_client else keys["server_write_key"]
            iv = keys["client_write_iv"] if is_client else keys["server_write_iv"]
            plaintext = decrypt_aead(rec, key, iv)
            if plaintext is not None:
                verified += 1
                coap_ok = is_coap(plaintext)
                summary = coap_header_summary(plaintext) if coap_ok else "not CoAP"
                row = {
                    "frame": frame_num,
                    "direction": "tracker->phone" if is_client else "phone->tracker",
                    "ip_id": ip_id,
                    "epoch": rec["epoch"],
                    "seq": rec["seq_num"],
                    "ciphertext_len": rec["length"],
                    "plaintext_len": len(plaintext),
                    "tag_valid": True,
                    "coap": coap_ok,
                    "coap_summary": summary,
                    "plaintext_hex": plaintext[:128].hex(),
                }
                decrypted_rows.append(row)
                sample_file = OUTDIR / f"sample_frame{frame_num}_{'client' if is_client else 'server'}.bin"
                sample_file.write_bytes(plaintext)
                plaintext_samples.append({
                    "frame": frame_num,
                    "direction": "tracker->phone" if is_client else "phone->tracker",
                    "file": sample_file.name,
                    "summary": summary,
                    "first_128_hex": plaintext[:128].hex(),
                })
            else:
                decrypted_rows.append({
                    "frame": frame_num,
                    "direction": "tracker->phone" if is_client else "phone->tracker",
                    "ip_id": ip_id,
                    "epoch": rec["epoch"],
                    "seq": rec["seq_num"],
                    "ciphertext_len": rec["length"],
                    "plaintext_len": 0,
                    "tag_valid": False,
                    "coap": False,
                    "coap_summary": "",
                    "plaintext_hex": "",
                })

    print(f"[+] Application-data records inspected: {total_app}")
    print(f"[+] Records with valid CCM tag under bootstrap key: {verified}")

    # Write CSVs
    csv_path = OUTDIR / "bootstrap_decryption_results.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(decrypted_rows[0].keys()) if decrypted_rows else [])
        writer.writeheader()
        writer.writerows(decrypted_rows)
    print(f"[+] Wrote {csv_path}")

    if plaintext_samples:
        sample_csv = OUTDIR / "decrypted_coap_samples.csv"
        with sample_csv.open("w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=["frame", "direction", "file", "summary", "first_128_hex"])
            writer.writeheader()
            writer.writerows(plaintext_samples)
        print(f"[+] Wrote {sample_csv}")

    # Summary file
    with (OUTDIR / "decryption_summary.txt").open("w", encoding="utf-8") as f:
        f.write(f"Bootstrap PSK: {BOOTSTRAP_PSK.hex()}\n")
        f.write(f"client_random: {client_random.hex()}\n")
        f.write(f"server_random: {server_random.hex()}\n")
        f.write(f"master_secret: {keys['master_secret'].hex()}\n")
        f.write(f"client_write_key: {keys['client_write_key'].hex()}\n")
        f.write(f"server_write_key: {keys['server_write_key'].hex()}\n")
        f.write(f"client_write_iv:  {keys['client_write_iv'].hex()}\n")
        f.write(f"server_write_iv:  {keys['server_write_iv'].hex()}\n")
        f.write(f"\nTotal application-data records: {total_app}\n")
        f.write(f"Verified under bootstrap key: {verified}\n")
        f.write(f"CoAP-looking samples: {sum(1 for s in plaintext_samples if 'ver=1' in s['summary'])}\n")


if __name__ == "__main__":
    main()
