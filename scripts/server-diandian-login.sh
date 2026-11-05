#!/usr/bin/env bash
# Independent viewer, loopback only; Chrome persists its own session on the server.
set -euo pipefail
ROOT=/opt/iaa-radar
PROFILE="$ROOT/shared/browser-sessions/diandian"
SIGNAL="$PROFILE/login-finished"
case "${1:-status}" in
 start)
  systemctl stop iaa-radar-profiles
  systemctl stop iaa-radar-dd-expiry.timer iaa-radar-dd-expiry.service iaa-radar-dd-login iaa-radar-dd-web iaa-radar-dd-display 2>/dev/null || true
  systemctl reset-failed iaa-radar-dd-login iaa-radar-dd-web iaa-radar-dd-display 2>/dev/null || true
  install -d -m 700 -o iaa-radar -g iaa-radar "$PROFILE"
  rm -f -- "$SIGNAL"
  systemd-run --unit=iaa-radar-dd-display --uid=iaa-radar --property=RuntimeMaxSec=2100 --property=MemoryMax=192M /usr/bin/Xvnc :92 -rfbport 5992 -localhost -SecurityTypes None -geometry 1440x1000 -depth 24 -AlwaysShared -nolisten tcp
  systemd-run --unit=iaa-radar-dd-web --uid=iaa-radar --property=RuntimeMaxSec=2100 --property=MemoryMax=128M /usr/bin/websockify --web=/usr/share/novnc 127.0.0.1:6082 127.0.0.1:5992
  systemd-run --unit=iaa-radar-dd-login --uid=iaa-radar --setenv=DISPLAY=:92 --property=RuntimeMaxSec=1800 --property=MemoryMax=1200M --property=WorkingDirectory="$ROOT/shared" /usr/bin/java -Xmx160m -Dloader.main=com.youkia.capture.browser.BrowserLoginCommand -cp "$ROOT/current/iaa-radar-api-0.1.0-SNAPSHOT.jar" org.springframework.boot.loader.launch.PropertiesLauncher --source diandian --profile "$PROFILE" --channel chrome --finish-file "$SIGNAL"
  systemd-run --unit=iaa-radar-dd-expiry --on-active=35m /bin/bash -c 'systemctl stop iaa-radar-dd-login iaa-radar-dd-web iaa-radar-dd-display 2>/dev/null; systemctl start iaa-radar-profiles'
  echo 'DIANDIAN_LOGIN_READY: temporary SSH viewer to loopback 6082.'
  ;;
 finish)
  trap 'systemctl start iaa-radar-profiles' EXIT
  systemctl stop iaa-radar-dd-expiry.timer iaa-radar-dd-expiry.service 2>/dev/null || true
  touch "$SIGNAL"
  for i in {1..15}; do systemctl is-active --quiet iaa-radar-dd-login || break; sleep 1; done
  systemctl stop iaa-radar-dd-login iaa-radar-dd-web iaa-radar-dd-display 2>/dev/null || true
  echo 'DIANDIAN_SESSION_SAVED: next actual capture verifies access.'
  ;;
 status) systemctl is-active iaa-radar-profiles iaa-radar-dd-login || true;;
 *) exit 2;;
esac
