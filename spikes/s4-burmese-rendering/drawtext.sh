#!/usr/bin/env bash
set -euo pipefail
: "${NOTO_MYANMAR_FONT:?Set NOTO_MYANMAR_FONT to a TTF file}"
: "${PYIDAUNGSU_FONT:?Set PYIDAUNGSU_FONT to a TTF file}"
FFMPEG=${FFMPEG:-ffmpeg}
cd "$(dirname "$0")"
mkdir -p out
printf '%s\n%s\n' 'မိုးရွာနေတဲ့ ညမှာ တစ်ယောက်တည်း' 'မြန်မာ စာလုံးဆင့် ကမ္ဘာ' > out/text.txt
for name in noto pyidaungsu; do
  if [[ $name == noto ]]; then font=$NOTO_MYANMAR_FONT; else font=$PYIDAUNGSU_FONT; fi
  # Filter paths are copied into the working directory to avoid escaping path punctuation.
  cp "$font" out/font.ttf
  "$FFMPEG" -hide_banner -loglevel error -y -f lavfi -i 'color=c=black:s=1280x720:r=25:d=5' \
    -vf "drawtext=fontfile=out/font.ttf:textfile=out/text.txt:fontcolor=white:fontsize=52:x=50:y=200" \
    -c:v mpeg4 -q:v 3 out/"$name".mp4
  "$FFMPEG" -hide_banner -loglevel error -y -i out/"$name".mp4 -frames:v 1 -update 1 out/"$name"-drawtext.png
done
rm out/font.ttf
