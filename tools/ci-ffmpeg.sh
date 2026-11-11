#!/usr/bin/env bash
# CI only (D-28): puts an x86-64 ffmpeg with libx264 + libmp3lame on PATH for tools/selftest-fixtures.sh.
# First a pinned static build (SHA-256 checked, ~42 MB), else apt (bounded, tools/ci-apt-install.sh).
set -u
URL=https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz
SHA256=abda8d77ce8309141f83ab8edf0596834087c52467f6badf376a6a2a4c87cf67  # ffmpeg 7.0.2-static
DIR="${RUNNER_TEMP:-/tmp}/ffmpeg-static"
mkdir -p "$DIR"
if curl -sfL --retry 3 --connect-timeout 20 --max-time 300 -o "$DIR/ff.tar.xz" "$URL" \
  && echo "$SHA256  $DIR/ff.tar.xz" | sha256sum -c --quiet - \
  && tar -xJf "$DIR/ff.tar.xz" -C "$DIR" --strip-components=1; then
  echo "$DIR" >> "${GITHUB_PATH:-/dev/null}"
  "$DIR/ffmpeg" -hide_banner -version | head -1
  exit 0
fi
echo "::warning::static ffmpeg download or checksum failed; trying apt"
exec bash "$(dirname "$0")/ci-apt-install.sh" ffmpeg
