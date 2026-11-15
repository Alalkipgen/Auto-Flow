#!/usr/bin/env bash
# CI (D-61): launch the packaged desktop app headlessly and fail if it does not come up cleanly.
# Fails when: the app exits early, an "Error" dialog window appears, the "Auto Flow" window never appears,
# or the log contains an exception / the missing-Main-dispatcher message.
# Needs Xvfb + xdotool (installed on demand). Usage: tools/desktop-smoke.sh [path/to/bin/AutoFlow]
set -u
APP="${1:-app-desktop/build/compose/binaries/main/app/AutoFlow/bin/AutoFlow}"
SECS="${SMOKE_SECONDS:-30}"
OUT="${SMOKE_OUT:-${RUNNER_TEMP:-/tmp}/desktop-smoke}"
mkdir -p "$OUT"
[ -x "$APP" ] || { echo "::error::packaged app not found: $APP"; exit 1; }
command -v Xvfb > /dev/null && command -v xdotool > /dev/null || bash "$(dirname "$0")/ci-apt-install.sh" xvfb xdotool

export HOME="$(mktemp -d)"   # fresh user: no settings, no projects (first-run path)
export DISPLAY=:97
Xvfb "$DISPLAY" -screen 0 1280x800x24 > /dev/null 2>&1 & XVFB=$!
trap 'kill "$APP_PID" "$XVFB" 2> /dev/null' EXIT
sleep 2

"$APP" > "$OUT/app.log" 2>&1 & APP_PID=$!
fail=""
for _ in $(seq "$SECS"); do
  sleep 1
  kill -0 "$APP_PID" 2> /dev/null || { fail="the app exited early (code $(wait "$APP_PID"; echo $?))"; break; }
  if xdotool search --onlyvisible --name '^Error$' > /dev/null 2>&1; then fail="an error dialog appeared"; break; fi
done
windows="$(for w in $(xdotool search --onlyvisible --name '.' 2> /dev/null); do xdotool getwindowname "$w"; done | sort -u)"
echo "Visible windows:"; echo "$windows" | sed 's/^/  /'
command -v import > /dev/null && import -display "$DISPLAY" -window root "$OUT/screen.png" 2> /dev/null
if [ -z "$fail" ] && ! echo "$windows" | grep -qx 'Auto Flow'; then fail="the 'Auto Flow' window did not appear"; fi
if [ -z "$fail" ] && grep -qE 'Main dispatcher is missing|Exception in thread|Could not create instance' "$OUT/app.log"; then
  fail="the log has a startup exception"
fi
if [ -n "$fail" ]; then
  echo "::error title=Desktop smoke test::$fail"
  tail -n 40 "$OUT/app.log"
  exit 1
fi
echo "Desktop smoke test passed: the packaged app ran ${SECS}s with its window and no error dialog."
