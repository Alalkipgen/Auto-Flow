#!/usr/bin/env bash
# Existing ffmpeg only; no image/GUI dependency or invented screenshots.
set -euo pipefail
root="${1:-ui/build/reports/ui-v3}"
[ -d "$root" ] || exit 0
mkdir -p "$root/contact-sheets"
find "$root" -type f -name '*.png' ! -path '*/contact-sheets/*' | sort | while IFS= read -r image; do
  name="$(basename "$image")"
  [[ "$name" =~ ^[0-9][0-9]-.*-(412|1440)-(en|my)-(1.0|2.0)-true.png$ ]] || continue
  concept="$(find docs/mockups/ui-v3 -name "${name:0:2}_*.webp" -print -quit)"
  [ -n "$concept" ] || { echo "Missing concept for $image" >&2; exit 1; }
  ffmpeg -v error -nostdin -y -threads 1 -i "$concept" -threads 1 -i "$image" \
    -filter_complex_threads 1 -filter_complex '[0:v]scale=-1:900[concept];[1:v]scale=-1:900[build];[concept][build]hstack=inputs=2' \
    -frames:v 1 -threads 1 "$root/contact-sheets/$name"
done
