#!/usr/bin/env bash
set -u
echo "A $(getent ahostsv4 prod.netprobe.xyz 2>/dev/null | awk '{print $1}' | sort -u | head)"
python3 - <<'PY'
import socket
for fam, name in ((socket.AF_INET, "A"), (socket.AF_INET6, "AAAA")):
    try:
        infos = socket.getaddrinfo("prod.netprobe.xyz", 443, fam, socket.SOCK_STREAM)
    except socket.gaierror as exc:
        print(name, "FAIL", exc)
        continue
    print(name, sorted({item[4][0] for item in infos}))
PY
