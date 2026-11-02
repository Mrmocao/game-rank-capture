#!/usr/bin/env bash
# Run as root on the application server. Viewer ports only bind to loopback.
set -euo pipefail
ROOT=/opt/iaa-radar
PROFILE="$ROOT/shared/browser-sessions/gravity"
SIGNAL="$PROFILE/login-finished"
case "${1:-status}" in
  start)
    systemctl stop iaa-radar-capture
    systemctl stop iaa-radar-login-expiry.timer iaa-radar-login-expiry.service 2>/dev/null || true
    systemctl stop iaa-radar-source-login iaa-radar-login-web iaa-radar-login-display 2>/dev/null || true
    systemctl reset-failed iaa-radar-source-login iaa-radar-login-web iaa-radar-login-display 2>/dev/null || true
    install -d -m 700 -o iaa-radar -g iaa-radar "$PROFILE"
    rm -f -- "$SIGNAL"
    systemd-run --unit=iaa-radar-login-display --uid=iaa-radar --property=RuntimeMaxSec=2100 --property=MemoryMax=192M /usr/bin/Xvnc :91 -rfbport 5991 -localhost -SecurityTypes None -geometry 1440x1000 -depth 24 -AlwaysShared -nolisten tcp
    systemd-run --unit=iaa-radar-login-web --uid=iaa-radar --property=RuntimeMaxSec=2100 --property=MemoryMax=128M /usr/bin/websockify --web=/usr/share/novnc 127.0.0.1:6081 127.0.0.1:5991
    systemd-run --unit=iaa-radar-source-login --uid=iaa-radar --setenv=DISPLAY=:91 --property=MemoryMax=900M --property=WorkingDirectory="$ROOT/shared" /usr/bin/java -Xmx160m -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dloader.main=com.youkia.capture.browser.BrowserLoginCommand -cp "$ROOT/current/iaa-radar-api-0.1.0-SNAPSHOT.jar" org.springframework.boot.loader.launch.PropertiesLauncher --profile "$PROFILE" --channel chrome --finish-file "$SIGNAL"
    systemd-run --unit=iaa-radar-login-expiry --on-active=35m /bin/bash -c 'systemctl stop iaa-radar-source-login iaa-radar-login-web iaa-radar-login-display 2>/dev/null; systemctl start iaa-radar-capture'
    echo 'SOURCE_LOGIN_READY: use a temporary SSH tunnel to 127.0.0.1:6081, then run finish.'
    ;;
  finish)
    trap 'systemctl start iaa-radar-capture iaa-radar-capture-reports' EXIT
    systemctl stop iaa-radar-login-expiry.timer iaa-radar-login-expiry.service 2>/dev/null || true
    touch "$SIGNAL"
    for i in {1..15}; do systemctl is-active --quiet iaa-radar-source-login || break; sleep 1; done
    systemctl stop iaa-radar-source-login iaa-radar-login-web iaa-radar-login-display 2>/dev/null || true
    python3 - <<'PY'
import json,pathlib,os
p=pathlib.Path('/opt/iaa-radar/shared/deliveries/source-session.json')
s=json.loads(p.read_text(encoding='utf-8')) if p.exists() else {}
s.update(verificationRequested=True,status='VERIFYING')
tmp=p.with_suffix('.pending');tmp.write_text(json.dumps(s,ensure_ascii=False),encoding='utf-8');os.chmod(str(tmp),0o644);os.replace(str(tmp),str(p))
PY
    echo 'SESSION_SAVED: verifying real charts on the server.'
    if systemd-run --wait --collect --unit=iaa-radar-capture-login-check --uid=iaa-radar --property=EnvironmentFile=/etc/iaa-radar/app.env --property=WorkingDirectory="$ROOT/shared" --property=MemoryMax=1G /usr/bin/java -Xmx192m -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dloader.main=com.youkia.iaa.collection.capture.RankingCaptureCommand -cp "$ROOT/current/iaa-radar-api-0.1.0-SNAPSHOT.jar" org.springframework.boot.loader.launch.PropertiesLauncher --run --env-file "$ROOT/shared/capture-worker.env" > "$ROOT/shared/data/server-login-check.log" 2>&1; then
      echo 'SOURCE_VERIFICATION_COMPLETED: refresh Information Capture after the report bridge processes the batch.'
    else
      echo 'SOURCE_VERIFICATION_FAILED: the real source run failed; retained history is unchanged.'
      exit 1
    fi
    ;;
  status)
    systemctl is-active iaa-radar-capture iaa-radar-capture-reports iaa-radar-source-login || true
    ;;
  *) echo 'usage: server-capture-login.sh start|finish|status' >&2; exit 2;;
esac
