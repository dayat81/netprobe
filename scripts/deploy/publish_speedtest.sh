#!/usr/bin/env bash
# Publish MySignal OoklaServer-like HTTP speedtest on azvm nginx :8080.
# Run as root on azvm.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP_DST="/opt/mysignal-speedtest"
SNIPPET="/etc/nginx/snippets/speedtest-locations.conf"
UNIT_SRC="${ROOT}/scripts/deploy/mysignal-speedtest.service"
PETA_AVAILABLE="/etc/nginx/sites-available/peta-degradasi"
PETA_ENABLED="/etc/nginx/sites-enabled/peta-degradasi"
PORT=8080

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root (sudo)" >&2
  exit 1
fi

if [[ ! -f "${ROOT}/server/speedtest/http_server.py" ]]; then
  echo "missing ${ROOT}/server/speedtest/http_server.py" >&2
  exit 1
fi

install -d -m 0755 "$APP_DST" /etc/nginx/snippets /etc/systemd/system
install -m 0755 "${ROOT}/server/speedtest/http_server.py" "${APP_DST}/http_server.py"
install -m 0644 "${ROOT}/scripts/deploy/nginx/speedtest-locations.conf" "$SNIPPET"
install -m 0644 "$UNIT_SRC" /etc/systemd/system/mysignal-speedtest.service

include_speedtest() {
  local site="$1"
  [[ -f "$site" ]] || return 1
  if grep -q 'speedtest-locations.conf' "$site"; then
    return 0
  fi
  local tmp
  tmp="$(mktemp)"
  awk -v inc="    include snippets/speedtest-locations.conf;" '
    BEGIN { done=0 }
    {
      print
      if (!done && $0 ~ /^server[[:space:]]*\{/) {
        print inc
        done=1
      }
    }
    END { if (!done) exit 2 }
  ' "$site" > "$tmp"
  install -m 0644 "$tmp" "$site"
  rm -f "$tmp"
}

if [[ ! -f "$PETA_ENABLED" && ! -f "$PETA_AVAILABLE" ]]; then
  echo "missing peta-degradasi nginx site for listen ${PORT}" >&2
  exit 1
fi
include_speedtest "$PETA_ENABLED" || true
include_speedtest "$PETA_AVAILABLE" || true
if ! grep -q 'speedtest-locations.conf' "$PETA_ENABLED" 2>/dev/null && ! grep -q 'speedtest-locations.conf' "$PETA_AVAILABLE" 2>/dev/null; then
  echo "failed to include speedtest snippet" >&2
  exit 1
fi

id www-data >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin www-data
systemctl daemon-reload
systemctl enable --now mysignal-speedtest.service
systemctl restart mysignal-speedtest.service

nginx -t
systemctl reload nginx

sleep 0.4
echo "== local backend =="
curl -fsS "http://127.0.0.1:8099/speedtest/hello"
echo
echo "== nginx :${PORT} =="
curl -fsS "http://127.0.0.1:${PORT}/speedtest/hello"
echo
curl -fsS -o /tmp/speedtest-dl.bin -w "download %{http_code} size %{size_download}\n" \
  "http://127.0.0.1:${PORT}/speedtest/download?size=4096"
python3 - <<'PY'
import os
size = os.path.getsize("/tmp/speedtest-dl.bin")
assert size == 4096, size
print("download bytes ok", size)
PY
curl -fsS -o /dev/null -D - -X POST --data-binary @/tmp/speedtest-dl.bin \
  "http://127.0.0.1:${PORT}/speedtest/upload" | tr -d '\r' | grep -E 'HTTP/|X-Received-Bytes' | head
echo "public: https://netprobe.xyz/speedtest/hello"
