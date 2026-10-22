#!/usr/bin/env bash
# Publish 360/720/1080 test MP4s for the MySignal video tab on nginx :8080.
# Run as root on azvm.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WEB="/var/www/video"
SNIPPET="/etc/nginx/snippets/video-locations.conf"
PETA_ENABLED="/etc/nginx/sites-enabled/peta-degradasi"
PETA_AVAILABLE="/etc/nginx/sites-available/peta-degradasi"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root (sudo)" >&2
  exit 1
fi
if ! command -v ffmpeg >/dev/null 2>&1; then
  apt-get update -qq
  apt-get install -y ffmpeg
fi

install -d -m 0755 "$WEB" /etc/nginx/snippets
install -m 0644 "${ROOT}/scripts/deploy/nginx/video-locations.conf" "$SNIPPET"

make_clip() {
  local name="$1" width="$2" height="$3" bitrate="$4"
  local dest="${WEB}/${name}.mp4"
  if [[ -f "$dest" && $(stat -c%s "$dest") -gt 10000 ]]; then
    echo "keep ${dest}"
    return
  fi
  ffmpeg -y -f lavfi -i "testsrc=size=${width}x${height}:rate=30" -t 15 \
    -c:v libx264 -pix_fmt yuv420p -b:v "$bitrate" -movflags +faststart \
    "$dest"
  chmod 0644 "$dest"
  echo "wrote ${dest}"
}

make_clip 360 640 360 400k
make_clip 720 1280 720 1500k
make_clip 1080 1920 1080 4000k

include_video() {
  local site="$1"
  [[ -f "$site" ]] || return 1
  if grep -q 'video-locations.conf' "$site"; then
    return 0
  fi
  local tmp
  tmp="$(mktemp)"
  awk -v inc="    include snippets/video-locations.conf;" '
    BEGIN { done=0 }
    { print; if (!done && $0 ~ /^server[[:space:]]*\{/) { print inc; done=1 } }
    END { if (!done) exit 2 }
  ' "$site" > "$tmp"
  install -m 0644 "$tmp" "$site"
  rm -f "$tmp"
}

if [[ ! -f "$PETA_ENABLED" && ! -f "$PETA_AVAILABLE" ]]; then
  echo "missing peta-degradasi nginx site" >&2
  exit 1
fi
include_video "$PETA_ENABLED" || true
include_video "$PETA_AVAILABLE" || true
if ! grep -q 'video-locations.conf' "$PETA_ENABLED" 2>/dev/null && ! grep -q 'video-locations.conf' "$PETA_AVAILABLE" 2>/dev/null; then
  echo "failed to include video snippet" >&2
  exit 1
fi

nginx -t
systemctl reload nginx
ok=0
for _ in 1 2 3 4 5; do
  if curl -fsSI "http://127.0.0.1:8080/video/360.mp4" | head -n 8; then
    ok=1
    break
  fi
  sleep 0.4
done
if [[ "$ok" -ne 1 ]]; then
  echo "video location did not answer 200" >&2
  exit 1
fi
curl -fsSI "http://127.0.0.1:8080/video/720.mp4" >/dev/null
curl -fsSI "http://127.0.0.1:8080/video/1080.mp4" >/dev/null
echo "public: https://netprobe.xyz/video/360.mp4"
