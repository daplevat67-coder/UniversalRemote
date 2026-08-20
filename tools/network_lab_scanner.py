#!/usr/bin/env python3
"""Small local-LAN reference scanner matching the Android Network Lab behavior.
Uses only the Python standard library. Targets are restricted to private IPv4 networks.
"""
from __future__ import annotations
import argparse
import concurrent.futures
import ipaddress
import socket
import subprocess
from dataclasses import dataclass, asdict
import json

DEFAULT_PORTS = [21, 22, 23, 53, 80, 139, 443, 445, 554, 631, 1883, 3389, 4352, 5000, 8008, 8009, 8080, 8443, 8883, 9100]

@dataclass
class Service:
    port: int
    name: str
    banner: str | None = None

SERVICE_NAMES = {
    21: "FTP", 22: "SSH", 23: "Telnet", 53: "DNS", 80: "HTTP", 139: "NetBIOS", 443: "HTTPS", 445: "SMB", 554: "RTSP",
    631: "IPP", 1883: "MQTT", 3389: "RDP", 4352: "PJLink", 5000: "HTTP/UPnP",
    8008: "Google Cast HTTP", 8009: "Google Cast", 8080: "HTTP-alt", 8443: "HTTPS-alt", 9100: "JetDirect",
}

def connect(host: str, port: int, timeout: float) -> socket.socket | None:
    try:
        s = socket.create_connection((host, port), timeout=timeout)
        s.settimeout(timeout)
        return s
    except OSError:
        return None

def banner(host: str, port: int, timeout: float) -> Service | None:
    s = connect(host, port, timeout)
    if not s:
        return None
    try:
        if port in (80, 5000, 8008, 8080):
            s.sendall(f"HEAD / HTTP/1.0\r\nHost: {host}\r\nConnection: close\r\n\r\n".encode("ascii"))
        data = s.recv(768)
        text = " ".join(data.decode("latin1", "replace").split())[:240] or None
        return Service(port, SERVICE_NAMES.get(port, "TCP"), text)
    except OSError:
        return Service(port, SERVICE_NAMES.get(port, "TCP"), None)
    finally:
        s.close()

def arp_mac(host: str) -> str | None:
    try:
        out = subprocess.check_output(["ip", "neigh", "show", host], text=True, stderr=subprocess.DEVNULL)
        parts = out.split()
        if "lladdr" in parts:
            return parts[parts.index("lladdr") + 1]
    except Exception:
        pass
    return None

def scan_host(host: str, ports: list[int], timeout: float) -> dict | None:
    quick = [80, 443, 22, 445, 631, 3389, 4352, 8008, 9100]
    if not any(connect_and_close(host, p, timeout) for p in quick):
        return None
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(16, len(ports) or 1)) as pool:
        services = [s for s in pool.map(lambda p: banner(host, p, timeout), ports) if s]
    try:
        name = socket.gethostbyaddr(host)[0]
    except OSError:
        name = None
    return {"ip": host, "mac": arp_mac(host), "hostname": name, "services": [asdict(s) for s in services]}

def connect_and_close(host: str, port: int, timeout: float) -> bool:
    s = connect(host, port, timeout)
    if not s:
        return False
    s.close()
    return True

def health_probe(host: str, port: int, timeout: float = 0.5) -> dict:
    """Three normal TCP connects; no malformed traffic or flooding."""
    import time
    samples: list[float] = []
    for i in range(3):
        started = time.perf_counter()
        s = connect(host, port, timeout)
        if s:
            samples.append((time.perf_counter() - started) * 1000)
            s.close()
        if i < 2:
            time.sleep(0.15)
    return {"attempts": 3, "successes": len(samples), "average_ms": (sum(samples) / len(samples)) if samples else None}

def wake_on_lan(mac: str, broadcast: str = "255.255.255.255") -> None:
    """Send a standard Wake-on-LAN magic packet to UDP 7 and 9."""
    raw = mac.replace(":", "").replace("-", "").strip()
    if len(raw) != 12 or any(c not in "0123456789abcdefABCDEF" for c in raw):
        raise ValueError("invalid MAC")
    mac_bytes = bytes.fromhex(raw)
    payload = b"\xff" * 6 + mac_bytes * 16
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for port in (7, 9):
            s.sendto(payload, (broadcast, port))

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("network", help="Private IPv4 CIDR, e.g. 192.168.1.0/24")
    ap.add_argument("--ports", default=",".join(map(str, DEFAULT_PORTS)))
    ap.add_argument("--timeout", type=float, default=0.25)
    ap.add_argument("--workers", type=int, default=32)
    args = ap.parse_args()
    net = ipaddress.ip_network(args.network, strict=False)
    if net.version != 4 or not net.is_private or net.num_addresses > 1024:
        raise SystemExit("Use a private IPv4 network with at most 1024 addresses")
    ports = [int(x) for x in args.ports.split(",") if x.strip().isdigit() and 1 <= int(x) <= 65535][:64]
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(4, min(args.workers, 64))) as pool:
        results = [r for r in pool.map(lambda ip: scan_host(str(ip), ports, args.timeout), net.hosts()) if r]
    print(json.dumps(results, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
