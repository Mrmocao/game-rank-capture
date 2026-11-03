#!/usr/bin/env bash
# Run as root from a deployed checkout. Never prints or replaces existing credentials.
set -euo pipefail
umask 077
[ "$(id -u)" -eq 0 ] || { echo 'Root installation required'; exit 1; }
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
test -f /opt/iaa-radar/shared/receive-capture.py
install -d -m 700 /etc/iaa-radar
if [ ! -f /etc/iaa-radar/overseas-tls.key ] && [ ! -f /etc/iaa-radar/overseas-tls.crt ]; then
 openssl req -x509 -newkey rsa:3072 -nodes -keyout /etc/iaa-radar/overseas-tls.key -out /etc/iaa-radar/overseas-tls.crt -days 730 -subj '/CN=iaa-overseas-receiver' -addext 'subjectAltName=IP:14.103.231.1'
fi
test -f /etc/iaa-radar/overseas-tls.key
test -f /etc/iaa-radar/overseas-tls.crt
python3 - <<'PY'
import secrets,pathlib
p=pathlib.Path('/etc/iaa-radar/overseas-token')
if not p.exists():p.write_text(secrets.token_urlsafe(48))
PY
chmod 600 /etc/iaa-radar/overseas-token /etc/iaa-radar/overseas-tls.key
install -o root -g root -m 644 "$script_dir/receive-overseas.py" "$script_dir/overseas-http-receiver.py" /opt/iaa-radar/shared/
install -o root -g root -m 644 "$script_dir/systemd/iaa-overseas-receiver.service" /etc/systemd/system/
install -o root -g root -m 644 "$script_dir/nginx/iaa-overseas.conf" /etc/nginx/conf.d/
nginx -t
systemctl daemon-reload
systemctl enable --now iaa-overseas-receiver
systemctl restart iaa-overseas-receiver
systemctl reload nginx
printf 'Receiver ready. Configure GitHub repository secrets through a secure operator session.\n'
