#!/usr/bin/env bash
# Publish the latest MySignal debug APK on nginx :8080 /mysignal.apk.
# Run as root on azvm. Prefers the APK left by tmp/build_mysignal_azvm.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WEB="/var/www/mysignal"
SNIPPET="/etc/nginx/snippets/mysignal-apk-locations.conf"
PETA_ENABLED="/etc/nginx/sites-enabled/peta-degradasi"
PETA_AVAILABLE="/etc/nginx/sites-available/peta-degradasi"
SRC="${1:-}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root (sudo)" >&2
  exit 1
fi

if [[ -z "$SRC" ]]; then
  if [[ -f /tmp/mysignal-debug.apk ]]; then
    SRC="/tmp/mysignal-debug.apk"
  else
    SRC="$(ls -1t /home/azureuser/osm-src/android/mysignal/app/build/outputs/apk/debug/mysignal-*.apk 2>/dev/null | head -n 1 || true)"
  fi
fi
if [[ -z "$SRC" || ! -f "$SRC" ]]; then
  echo "missing MySignal APK" >&2
  exit 1
fi

install -d -m 0755 "$WEB" /etc/nginx/snippets
install -m 0644 "$SRC" "${WEB}/mysignal.apk"
install -m 0644 "${ROOT}/scripts/deploy/nginx/mysignal-apk-locations.conf" "$SNIPPET"

include_apk() {
  local site="$1"
  [[ -f "$site" ]] || return 1
  if grep -q 'mysignal-apk-locations.conf' "$site"; then
    return 0
  fi
  local tmp
  tmp="$(mktemp)"
  awk -v inc="    include snippets/mysignal-apk-locations.conf;" '
    BEGIN { done=0 }
    { print; if (!done && $0 ~ /^server[[:space:]]*\{/) { print inc; done=1 } }
    END { if (!done) exit 2 }
  ' "$site" > "$tmp"
  install -m 0644 "$tmp" "$site"
  rm -f "$tmp"
}

include_apk "$PETA_ENABLED" || true
include_apk "$PETA_AVAILABLE" || true
if ! grep -q 'mysignal-apk-locations.conf' "$PETA_ENABLED" 2>/dev/null && ! grep -q 'mysignal-apk-locations.conf' "$PETA_AVAILABLE" 2>/dev/null; then
  echo "failed to include apk snippet" >&2
  exit 1
fi

nginx -t
systemctl reload nginx
ok=0
for _ in 1 2 3 4 5; do
  if curl -fsSI "http://127.0.0.1:8080/mysignal.apk" | head -n 12; then
    ok=1
    break
  fi
  sleep 0.4
done
if [[ "$ok" -ne 1 ]]; then
  echo "mysignal.apk did not answer 200" >&2
  exit 1
fi
echo "public: https://netprobe.xyz/mysignal.apk"
