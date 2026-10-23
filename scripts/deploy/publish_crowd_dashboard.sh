#!/usr/bin/env bash
# Publish the MySignal crowd dashboard on azvm nginx :8080 /crowd/.
# Run as root on azvm.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DST="/data/opensignal/scripts"
SNIPPET="/etc/nginx/snippets/crowd-dashboard.conf"
PETA="/etc/nginx/sites-enabled/peta-degradasi"
DB="/var/lib/opensignal-mirror/collector.sqlite"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root (sudo)" >&2
  exit 1
fi

install -d -m 0755 "$DST" /etc/nginx/snippets /etc/systemd/system
install -m 0644 "${ROOT}/scripts/opensignal_mirror/crowd_dashboard.py" "${DST}/crowd_dashboard.py"
install -m 0644 "${ROOT}/scripts/opensignal_mirror/crowd_dashboard.html" "${DST}/crowd_dashboard.html"
install -m 0644 "${ROOT}/scripts/opensignal_mirror/measure_otel.py" "${DST}/measure_otel.py"
install -m 0644 "${ROOT}/scripts/opensignal_mirror/measure_jev.py" "${DST}/measure_jev.py"
install -d -m 0755 /var/lib/opensignal-mirror/complaints
chown azureuser:azureuser /var/lib/opensignal-mirror/complaints
if [[ -f /etc/opensignal-mirror/hmac.key ]]; then
  setfacl -m u:azureuser:r /etc/opensignal-mirror/hmac.key 2>/dev/null || chmod a+r /etc/opensignal-mirror/hmac.key
fi
install -m 0644 "${ROOT}/scripts/deploy/nginx/crowd-dashboard.conf" "$SNIPPET"

if [[ -f "$DB" ]]; then
  setfacl -m u:azureuser:r "$DB" 2>/dev/null || chmod a+r "$DB"
fi

cat > /etc/systemd/system/mysignal-crowd.service <<'UNIT'
[Unit]
Description=MySignal crowdsource dashboard
After=network.target

[Service]
Type=simple
User=azureuser
Group=azureuser
WorkingDirectory=/data/opensignal/scripts
Environment=CLICKHOUSE_URL=http://127.0.0.1:8123
Environment=OSM_DB=/var/lib/opensignal-mirror/collector.sqlite
Environment=CROWD_PORT=8773
Environment=PUBLIC_BASE=https://netprobe.xyz
EnvironmentFile=-/data/opensignal/.env
ExecStart=/usr/bin/python3 /data/opensignal/scripts/crowd_dashboard.py
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
UNIT

if [[ -f "$PETA" ]] && ! grep -q 'crowd-dashboard.conf' "$PETA"; then
  tmp="$(mktemp)"
  awk -v inc="    include snippets/crowd-dashboard.conf;" '
    BEGIN { done=0 }
    { print; if (!done && $0 ~ /^server[[:space:]]*\{/) { print inc; done=1 } }
    END { if (!done) exit 2 }
  ' "$PETA" > "$tmp"
  install -m 0644 "$tmp" "$PETA"
  rm -f "$tmp"
fi

systemctl daemon-reload
systemctl enable --now mysignal-crowd.service
systemctl restart mysignal-crowd.service
nginx -t
systemctl reload nginx
sleep 0.4
curl -fsS -o /dev/null -w "page %{http_code}\n" http://127.0.0.1:8080/crowd/
curl -fsS "http://127.0.0.1:8080/crowd/api" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("devices", d.get("devices"), "radio", d.get("radio_count"), "speed", d.get("speedtest_count"), "video", d.get("video_count"), "poi", d.get("poi_count"), d.get("note") or "")'
echo "public: https://netprobe.xyz/crowd/"
