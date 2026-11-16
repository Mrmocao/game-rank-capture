#!/usr/bin/env bash
# Root-owned fixed entrypoint. Called only by the three dedicated systemd units.
set -euo pipefail
ROOT=/opt/iaa-radar
exec 9>/run/iaa-radar-web-login.lock
flock -n 9 || exit 0
write_state() {
 python3 - "$1" <<'PY'
import datetime,json,os,pathlib,sys
now=datetime.datetime.now(datetime.timezone.utc)
p=pathlib.Path('/opt/iaa-radar/shared/deliveries/web-login.json')
data={'state':sys.argv[1],'updatedAt':now.isoformat(),'expiresAt':(now+datetime.timedelta(minutes=30)).isoformat()}
t=p.with_suffix('.tmp');t.write_text(json.dumps(data));os.chmod(t,0o644);t.replace(p)
PY
}
cleanup() {
 systemctl stop iaa-radar-source-login iaa-radar-login-web iaa-radar-login-display 2>/dev/null || true
 systemctl start iaa-radar-capture iaa-radar-capture-reports
}
failed() { cleanup; write_state FAILED; }
trap failed ERR
case "${1:-}" in
 start)
  write_state OPENING
  bash "$ROOT/shared/server-capture-login.sh" start
  # Replace the legacy cleanup timer with a state-aware cleanup.
  systemctl stop iaa-radar-login-expiry.timer iaa-radar-login-expiry.service 2>/dev/null || true
  systemctl stop iaa-radar-web-login-expiry.timer iaa-radar-web-login-expiry.service 2>/dev/null || true
  systemd-run --unit=iaa-radar-web-login-expiry --on-active=31m /bin/bash "$ROOT/tools/server-web-capture-login.sh" cancel
  ready=false
  for i in {1..20}; do
   if systemctl is-active --quiet iaa-radar-source-login && curl -fsS http://127.0.0.1:6081/vnc.html >/dev/null; then ready=true;break;fi
   sleep 1
  done
  test "$ready" = true
  write_state READY
  ;;
 finish)
  write_state VERIFYING
  systemctl stop iaa-radar-web-login-expiry.timer 2>/dev/null || true
  bash "$ROOT/shared/server-capture-login.sh" finish
  # Command completion is not proof of authentication. UI reads actual source runs.
  write_state CLOSED
  ;;
 cancel)
  systemctl stop iaa-radar-web-login-expiry.timer iaa-radar-login-expiry.timer 2>/dev/null || true
  touch "$ROOT/shared/browser-sessions/gravity/login-finished"
  for i in {1..10}; do systemctl is-active --quiet iaa-radar-source-login || break;sleep 1;done
  cleanup
  write_state CLOSED
  ;;
 *) exit 2;;
esac
