#!/usr/bin/env bash
# F-7 (D-96): the packaged Linux app image answers `--help` (exit 0) and a missing package (exit 2) without a display,
# then goes into AutoFlow-linux-x64-<sha7>.tar.gz for servers (`./AutoFlow/bin/AutoFlow --render pkg.zip --out out/`).
set -euo pipefail
out="${SMOKE_OUT:?Set SMOKE_OUT to the desktop smoke evidence directory}"
app_dir="app-desktop/build/compose/binaries/main/app"
bin="$app_dir/AutoFlow/bin/AutoFlow"
mkdir -p "$out"
[[ -x "$bin" ]] || { echo "::error::$bin is missing (createDistributable did not run)"; exit 1; }

env -u DISPLAY "$bin" --help > "$out/render-help.txt" 2>&1 || { echo "::error::AutoFlow --help failed"; cat "$out/render-help.txt"; exit 1; }
grep -q "Usage: AutoFlow --render" "$out/render-help.txt" || { echo "::error::AutoFlow --help printed no usage"; cat "$out/render-help.txt"; exit 1; }

set +e
env -u DISPLAY "$bin" --render /nonexistent/package.zip > "$out/render-missing.txt" 2>&1
code=$?
set -e
cat "$out/render-missing.txt"
[[ "$code" == 2 ]] || { echo "::error::AutoFlow --render /nonexistent/package.zip exited $code, expected 2"; exit 1; }

full="${GITHUB_SHA:-$(git rev-parse HEAD)}"
sha="${full::7}"
dist="${RUNNER_TEMP:-/tmp}/linux-app"
mkdir -p "$dist"
tar -C "$app_dir" -czf "$dist/AutoFlow-linux-x64-$sha.tar.gz" AutoFlow
(cd "$dist" && sha256sum "AutoFlow-linux-x64-$sha.tar.gz" > "AutoFlow-linux-x64-$sha.tar.gz.sha256")
ls -l "$dist"
if [[ -n "${GITHUB_ENV:-}" ]]; then echo "LINUX_APP=autoflow-linux-x64-$sha" >> "$GITHUB_ENV"; fi
echo "Linux app: $dist/AutoFlow-linux-x64-$sha.tar.gz"
