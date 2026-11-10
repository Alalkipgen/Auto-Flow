#!/usr/bin/env bash
# S4 (Q-02): Burmese text on a 5 s black 1080x1920 clip via A) drawtext, B) libass (.ass + ass=), C) Skia/Compose PNG + overlay.
# Usage: FFMPEG=ffmpeg FONTS_DIR=dir OUT=dir [SKIA_DIR=dir with <font>-skia.png] bash run.sh
# Frames are extracted at t=2.5 s (karaoke line in B is half highlighted there).
set -uo pipefail
FFMPEG=${FFMPEG:-ffmpeg}
here=$(cd "$(dirname "$0")" && pwd)
: "${FONTS_DIR:?}" "${OUT:?}"
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd); FONTS_DIR=$(cd "$FONTS_DIR" && pwd)
cd "$OUT"   # filters use plain relative paths (no escaping of drive letters/colons)
mkdir -p fonts && cp "$FONTS_DIR"/*.ttf fonts/
line1=$(sed -n 1p "$here/lines.txt"); line2=$(sed -n 2p "$here/lines.txt")
printf '%s' "$line1" > line1.txt; printf '%s' "$line2" > line2.txt
{
  "$FFMPEG" -hide_banner -version | head -1
  "$FFMPEG" -hide_banner -buildconf | tr -s ' ' '\n' | grep -E 'harfbuzz|libass|freetype|fribidi|fontconfig' | sort -u
  echo "filters:"; "$FFMPEG" -hide_banner -filters 2>/dev/null | grep -E ' (drawtext|ass|subtitles|overlay) '
} > ffmpeg-info.txt 2>&1
black=(-f lavfi -i "color=c=black:s=1080x1920:r=30:d=5")
frame() { "$FFMPEG" -hide_banner -loglevel error -y -ss 2.5 -i "$1" -frames:v 1 -update 1 "$2"; }
status() { echo "$1: $2" | tee -a status.txt; }
: > status.txt
for f in fonts/*.ttf; do
  key=$(basename "$f" .ttf | sed 's/-.*//' | tr 'A-Z' 'a-z')        # notosansmyanmar | pyidaungsu
  fam=$(case $key in notosansmyanmar) echo "Noto Sans Myanmar";; pyidaungsu) echo "Pyidaungsu";; esac)
  cp "$f" font.ttf
  # A) drawtext
  if "$FFMPEG" -hide_banner -loglevel error -y "${black[@]}" \
      -vf "drawtext=fontfile=font.ttf:textfile=line1.txt:fontcolor=white:fontsize=64:x=(w-text_w)/2:y=820,drawtext=fontfile=font.ttf:textfile=line2.txt:fontcolor=white:fontsize=64:x=(w-text_w)/2:y=940" \
      -c:v libx264 -pix_fmt yuv420p "A-$key.mp4" 2> "A-$key.err"; then frame "A-$key.mp4" "A-drawtext-$key.png"; status "A $key" ok; else status "A $key" "FAILED ($(head -c 200 "A-$key.err"))"; fi
  # B) libass
  sed -e "s/@FONT@/$fam/g" -e "s/@LINE1@/$line1/" -e "s/@LINE2@/$line2/" "$here/lyrics.template.ass" > "lyrics-$key.ass"
  if "$FFMPEG" -hide_banner -loglevel error -y "${black[@]}" -vf "ass=lyrics-$key.ass:fontsdir=fonts" \
      -c:v libx264 -pix_fmt yuv420p "B-$key.mp4" 2> "B-$key.err"; then frame "B-$key.mp4" "B-libass-$key.png"; status "B $key" ok; else status "B $key" "FAILED ($(head -c 200 "B-$key.err"))"; fi
  # C) Skia/Compose PNG overlay
  if [ -n "${SKIA_DIR:-}" ] && [ -f "$SKIA_DIR/$key-skia.png" ]; then
    cp "$SKIA_DIR/$key-skia.png" "text-$key.png"
    if "$FFMPEG" -hide_banner -loglevel error -y "${black[@]}" -loop 1 -i "text-$key.png" \
        -filter_complex "[0][1]overlay=0:0:shortest=1,format=yuv420p" -c:v libx264 "C-$key.mp4" 2> "C-$key.err"; then
      frame "C-$key.mp4" "C-skia-$key.png"; status "C $key" ok; else status "C $key" "FAILED ($(head -c 200 "C-$key.err"))"; fi
  else status "C $key" "skipped (no Skia PNG)"; fi
done
rm -f font.ttf *.mp4; rm -rf fonts; find . -name "*.err" -empty -delete
