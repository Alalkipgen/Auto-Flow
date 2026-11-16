#!/usr/bin/env bash
# Matching source beside desktop QA binaries (D-69); no native VLC downloads.
set -euo pipefail
out="${1:?output directory required}"; mkdir -p "$out"; out="$(cd "$out" && pwd)"
sha="$(git rev-parse HEAD)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/AutoFlow-$sha/dependency-sources"
git archive "$sha" | tar -x -C "$tmp/AutoFlow-$sha"
fetch() {
  local group="$1" artifact="$2" version="$3" expected="$4"
  local name="$artifact-$version-sources.jar"
  curl -fLsS --retry 3 "https://repo.maven.apache.org/maven2/${group//.//}/$artifact/$version/$name" -o "$tmp/AutoFlow-$sha/dependency-sources/$name"
  echo "$expected  $tmp/AutoFlow-$sha/dependency-sources/$name" | sha256sum -c -
}
fetch uk.co.caprica vlcj 4.8.3 c282af7e07d512048ab04f10eb7e4220a93f4f7965a4cee55a0103c613757680
fetch uk.co.caprica vlcj-natives 4.8.3 fc15a665094ffd09bcc5055d6edcf4ac2e5db7c10cb79b07b05424546a7349e7
fetch net.java.dev.jna jna 5.17.0 1e8dd4205deab6eb9d6045ad9e69a8754fc21029d56ede1dcd9f22a5e06571a7
fetch net.java.dev.jna jna-platform 5.17.0 63f67968e5714f854e8de84330aa371a33221871f8b110917769f15e517a6a24
printf 'Source commit: %s\nBuild: ./gradlew :app-desktop:packageExe (Windows, JDK25, WiX3.14.1; see .github/workflows/ci.yml).\nNo native VLC is bundled.\n' "$sha" > "$tmp/AutoFlow-$sha/SOURCE-MANIFEST.txt"
# Python is present on both CI hosts; Git Bash on Windows does not ship the zip command.
python - "$tmp" "$out/AutoFlow-${sha:0:7}-desktop-source" "AutoFlow-$sha" <<'PYZIP'
import shutil, sys
shutil.make_archive(sys.argv[2], "zip", root_dir=sys.argv[1], base_dir=sys.argv[3])
PYZIP
(cd "$out" && sha256sum "AutoFlow-${sha:0:7}-desktop-source.zip" > "AutoFlow-${sha:0:7}-desktop-source.zip.sha256")
