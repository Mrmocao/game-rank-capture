#!/usr/bin/env bash
set -euo pipefail
ROOT=/opt/iaa-radar
# Run from a release containing scripts/. No source credential is exported.
install -m 755 -o root -g root scripts/server-web-capture-login.sh "$ROOT/tools/server-web-capture-login.sh"
for action in start finish cancel; do
 cat >"/etc/systemd/system/iaa-radar-web-login-$action.service" <<EOF
[Unit]
Description=IAA capture web login $action
After=network-online.target
[Service]
Type=oneshot
ExecStart=/bin/bash $ROOT/tools/server-web-capture-login.sh $action
TimeoutStartSec=600
UMask=0077
EOF
done
cat >/etc/sudoers.d/iaa-radar-web-login <<'EOF'
iaa-radar ALL=(root) NOPASSWD: /usr/bin/systemctl start --no-block iaa-radar-web-login-start.service
iaa-radar ALL=(root) NOPASSWD: /usr/bin/systemctl start --no-block iaa-radar-web-login-finish.service
iaa-radar ALL=(root) NOPASSWD: /usr/bin/systemctl start --no-block iaa-radar-web-login-cancel.service
EOF
chmod 440 /etc/sudoers.d/iaa-radar-web-login
visudo -cf /etc/sudoers.d/iaa-radar-web-login
systemctl daemon-reload
