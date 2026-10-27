import struct
from collections import Counter
from pathlib import Path

FILE = Path(r"G:\Dev\BlueBit\btsnoop_hci.log")
OUT = FILE.with_name("bluetooth_analysis.txt")

def main():
    raw = FILE.read_bytes()
    if not raw.startswith(b"btsnoop\0"):
        raise ValueError("This does not have a standard btsnoop header.")

    version, linktype = struct.unpack(">II", raw[8:16])
    pos = 16
    total = 0
    packet_types = Counter()
    events = Counter()
    att_ops = Counter()
    errors = Counter()
    connections = []
    advertisements = []
    disconnects = []
    other_inspire = []
    device_names = Counter()

    while pos + 24 <= len(raw):
        orig, inc, flags, drops, ts = struct.unpack(">IIIIQ", raw[pos:pos+24])
        pos += 24
        if inc > len(raw) - pos:
            break
        data = raw[pos:pos+inc]
        pos += inc
        total += 1
        if not data:
            continue

        # Standard HCI UART (H4) packets have a packet-type byte.
        ptype = data[0]
        packet_types[ptype] += 1

        if ptype == 4 and len(data) >= 3:  # HCI event
            code, plen = data[1], data[2]
            p = data[3:3+plen]
            events[code] += 1

            # Disconnection Complete
            if code == 0x05 and len(p) >= 4:
                handle = int.from_bytes(p[1:3], "little")
                reason = p[3]
                disconnects.append(
                    f"packet {total}: handle=0x{handle:04x}, reason=0x{reason:02x}"
                )

            # LE Meta Event
            if code == 0x3e and p:
                sub = p[0]

                # LE Connection Complete / Enhanced Connection Complete
                if sub in (0x01, 0x0a) and len(p) >= 12:
                    status = p[1]
                    handle = int.from_bytes(p[2:4], "little")
                    addr_type = p[5]
                    addr = ":".join(f"{b:02X}" for b in p[6:12][::-1])
                    connections.append(
                        f"packet {total}: subevent=0x{sub:02x}, "
                        f"status=0x{status:02x}, handle=0x{handle:04x}, "
                        f"peer={addr}, address_type={addr_type}"
                    )

                # LE Advertising Report (legacy)
                if sub == 0x02 and len(p) >= 2:
                    count = p[1]
                    i = 2
                    for _ in range(count):
                        if i + 9 > len(p):
                            break
                        event_type = p[i]
                        addr_type = p[i+1]
                        addr_bytes = p[i+2:i+8]
                        n = p[i+8]
                        i += 9
                        if i + n + 1 > len(p):
                            break
                        adv = p[i:i+n]
                        rssi = struct.unpack("b", p[i+n:i+n+1])[0]
                        i += n + 1
                        addr = ":".join(f"{b:02X}" for b in addr_bytes[::-1])

                        # Parse BLE advertising data structures.
                        j = 0
                        name = ""
                        while j < len(adv):
                            size = adv[j]
                            if size == 0 or j + 1 + size > len(adv):
                                break
                            typ = adv[j+1]
                            val = adv[j+2:j+1+size]
                            if typ in (0x08, 0x09):
                                name = val.decode("utf-8", "replace")
                            j += 1 + size

                        if name:
                            device_names[name] += 1
                        if (b"Inspire 3" in adv or "Inspire 3" in name
                                or addr.upper() == "D8:9E:C1:BA:3D:E4"):
                            advertisements.append(
                                f"packet {total}: address={addr}, name={name!r}, "
                                f"RSSI={rssi} dBm, event_type={event_type}, "
                                f"address_type={addr_type}"
                            )

                # LE Extended Advertising Report
                if sub == 0x0d and len(p) >= 2:
                    count = p[1]
                    i = 2
                    for _ in range(count):
                        if i + 24 > len(p):
                            break
                        event_type = int.from_bytes(p[i:i+2], "little")
                        addr_type = p[i+2]
                        addr_bytes = p[i+3:i+9]
                        # Fields before data length total 24 bytes.
                        data_len = p[i+23]
                        addr = ":".join(f"{b:02X}" for b in addr_bytes[::-1])
                        i += 24
                        if i + data_len + 1 > len(p):
                            break
                        adv = p[i:i+data_len]
                        rssi = struct.unpack("b", p[i+data_len:i+data_len+1])[0]
                        i += data_len + 1
                        if b"Inspire 3" in adv or addr.upper() == "D8:9E:C1:BA:3D:E4":
                            advertisements.append(
                                f"packet {total}: EXT address={addr}, "
                                f"RSSI={rssi} dBm, event_type=0x{event_type:04x}"
                            )

        # ACL data: look for ATT traffic inside L2CAP.
        if ptype == 2 and len(data) >= 9:
            l2cap_len = int.from_bytes(data[5:7], "little")
            cid = int.from_bytes(data[7:9], "little")
            payload = data[9:9 + l2cap_len]
            if cid == 0x0004 and payload:
                op = payload[0]
                att_ops[op] += 1
                if op == 0x01 and len(payload) >= 5:
                    request_op = payload[1]
                    handle = int.from_bytes(payload[2:4], "little")
                    err = payload[4]
                    errors[(request_op, err)] += 1

    lines = [
        "Bluetooth HCI capture summary",
        f"File: {FILE}",
        f"Size: {len(raw):,} bytes",
        f"btsnoop version: {version}",
        f"link type: {linktype}",
        f"Packets parsed: {total:,}",
        "",
        "HCI packet types (4=event, 2=ACL):",
        str(packet_types),
        "",
        "HCI event counts:",
        str(events),
        "",
        "ATT opcode counts:",
        str(att_ops),
        "",
        "ATT error responses (request opcode, error code):",
        str(errors),
        "",
        "Device names seen in advertising data:",
    ]
    lines += [f"  {name!r}: {count}" for name, count in device_names.most_common(40)]
    lines += ["", "Inspire 3 advertising matches:"]
    lines += advertisements or ["  No matching advertising packets identified."]
    lines += ["", "LE connection events:"]
    lines += connections or ["  No LE connection-complete events identified."]
    lines += ["", "Disconnect events:"]
    lines += disconnects[-100:] or ["  No disconnection events identified."]
    lines += [
        "",
        "Note: a missing event here does not by itself prove that it never occurred.",
        "If packet types are mostly not 2 or 4, the capture may use another link type.",
    ]

    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"Analysis complete: {OUT}")
    print(f"Packets parsed: {total:,}")
    print(f"Inspire advertising matches: {len(advertisements)}")
    print(f"Connection events: {len(connections)}")
    print(f"ATT error types: {len(errors)}")

if __name__ == "__main__":
    main()
