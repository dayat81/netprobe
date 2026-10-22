#!/usr/bin/env bash
# Deploy the measurements dashboard (device / PLMN / ARFCN / RSRP-SINR / NR) to the Azure VM.
#   ./scripts/deploy/deploy_measure_dashboard.sh [ssh-host]
# Serves 127.0.0.1:8772 behind nginx at http://<vm>:8080/meas/
set -euo pipefail

HOST="${1:-azvm}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/../opensignal_mirror"

scp -q "$SRC/measure_dashboard.py" "$SRC/measure_dashboard.html" "$SRC/measure_otel.py" "$HOST:/data/opensignal/scripts/"

ssh "$HOST" 'sudo -n tee /etc/systemd/system/opensignal-measure-dashboard.service >/dev/null <<UNIT
[Unit]
Description=OpenSignal measurements dashboard (device/PLMN/ARFCN/RSRP/SINR/NR)
After=network-online.target

[Service]
Type=simple
User=azureuser
WorkingDirectory=/data/opensignal/scripts
Environment=CLICKHOUSE_URL=http://127.0.0.1:8123
Environment=HOME=/home/azureuser
ExecStart=/data/opensignal/venv/bin/python /data/opensignal/scripts/measure_dashboard.py --host 127.0.0.1 --port 8772
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT'

ssh "$HOST" 'sudo -n tee /etc/nginx/snippets/measure-dashboard.conf >/dev/null <<CONF
location /meas/ {
    proxy_pass http://127.0.0.1:8772/;
    proxy_set_header Host \$host;
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_read_timeout 240s;
    proxy_send_timeout 240s;
}
location = /meas/api/interpret {
    proxy_pass http://127.0.0.1:8772/api/interpret;
    proxy_set_header Host \$host;
    proxy_read_timeout 240s;
    proxy_buffering off;
}
CONF'
ssh "$HOST" 'set -e
SITE=/etc/nginx/sites-enabled/peta-degradasi
if ! grep -q "measure-dashboard.conf" "$SITE"; then
  sudo -n sed -i "/include snippets\/opensignal-apk-locations.conf;/a\\    include snippets/measure-dashboard.conf;" "$SITE"
fi
sudo -n nginx -t
sudo -n systemctl daemon-reload
sudo -n systemctl enable --now opensignal-measure-dashboard.service
sudo -n systemctl restart opensignal-measure-dashboard.service
sudo -n systemctl reload nginx
sleep 2
systemctl --no-pager --lines=0 status opensignal-measure-dashboard.service | head -5'

ssh "$HOST" 'curl -sS -m 25 -o /dev/null -w "page %{http_code}\n" http://127.0.0.1:8080/meas/;
             curl -sS -m 30 "http://127.0.0.1:8080/meas/api/measure?minutes=15" -o /tmp/meas.json -w "api %{http_code} %{size_download} bytes\n";
             head -c 300 /tmp/meas.json; echo'
