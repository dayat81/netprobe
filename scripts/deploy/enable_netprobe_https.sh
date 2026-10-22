#!/usr/bin/env bash
# Issue a Let's Encrypt certificate for netprobe.xyz and serve the existing
# :8080 site on https://netprobe.xyz. Public :8080 redirects there.
# Run as root on azvm.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SITE="/etc/nginx/sites-available/netprobe-https.conf"
ENABLED="/etc/nginx/sites-enabled/netprobe-https.conf"
PETA="/etc/nginx/sites-enabled/peta-degradasi"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root (sudo)" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y certbot python3-certbot-nginx

install -d -m 0755 /var/www/certbot/.well-known/acme-challenge
install -m 0644 "${ROOT}/scripts/deploy/nginx/netprobe-https.conf" "$SITE"
ln -sfn "$SITE" "$ENABLED"

# Keep local checks on 127.0.0.1:8080. Public :8080 is the redirect server.
if grep -q 'listen 8080;' "$PETA"; then
  sed -i 's/listen 8080;/listen 127.0.0.1:8080;/' "$PETA"
fi
if grep -q 'server_name _;' "$PETA"; then
  sed -i 's/server_name _;/server_name localhost;/' "$PETA"
fi

# Certificate files are created by certbot. Nginx cannot load the 443 server before that.
python3 - <<'PY'
from pathlib import Path
path = Path("/etc/nginx/sites-enabled/netprobe-https.conf")
text = path.read_text()
start = text.find("server {\n    listen 443 ssl;")
if start < 0:
    raise SystemExit("443 server block not found")
kept = text[:start].rstrip() + "\n"
path.write_text(kept)
PY

nginx -t
systemctl reload nginx

if [[ ! -f /etc/letsencrypt/live/netprobe.xyz/fullchain.pem ]]; then
  certbot certonly --webroot -w /var/www/certbot \
    -d netprobe.xyz -d www.netprobe.xyz \
    --non-interactive --agree-tos --register-unsafely-without-email
fi
if [[ ! -f /etc/letsencrypt/options-ssl-nginx.conf ]]; then
  found="$(find /usr -name options-ssl-nginx.conf 2>/dev/null | head -n 1 || true)"
  if [[ -n "$found" ]]; then
    cp "$found" /etc/letsencrypt/options-ssl-nginx.conf
  else
    cat >/etc/letsencrypt/options-ssl-nginx.conf <<'SSL'
ssl_session_cache shared:le_nginx_SSL:10m;
ssl_session_timeout 1440m;
ssl_session_tickets off;
ssl_protocols TLSv1.2 TLSv1.3;
ssl_prefer_server_ciphers off;
ssl_ciphers "ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:DHE-RSA-AES128-GCM-SHA256:DHE-RSA-AES256-GCM-SHA384";
SSL
  fi
fi
if [[ ! -f /etc/letsencrypt/ssl-dhparams.pem ]]; then
  openssl dhparam -out /etc/letsencrypt/ssl-dhparams.pem 2048
fi

install -m 0644 "${ROOT}/scripts/deploy/nginx/netprobe-https.conf" "$SITE"
nginx -t
systemctl reload nginx
systemctl enable --now certbot.timer || true

echo "== local https =="
curl -fsSI --resolve netprobe.xyz:443:127.0.0.1 https://netprobe.xyz/mysignal.apk | head -n 12
echo "public: https://netprobe.xyz/mysignal.apk"
