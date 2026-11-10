#!/usr/bin/env bash
# S6 self-test fixtures, generated in CI (binary files are not committed) into <out>/selftest/:
#   clip1.mp4, clip2.mp4 — 8 s, 1080×1920 (9:16), 24 fps, H.264 + AAC, like a Flow download
#   song.mp3             — 20 s
#   lyrics.ass           — the S4 Burmese lines (spikes/s4-burmese-rendering), timed over 16 s
#   fonts/NotoSansMyanmar-Regular.ttf — OFL, pinned by commit and SHA-256
# Needs ffmpeg with libx264 + libmp3lame on the build machine (not the phone).
set -euo pipefail
OUT="${1:?usage: selftest-fixtures.sh <assets dir>}/selftest"
FONT_URL=https://raw.githubusercontent.com/notofonts/notofonts.github.io/3c93d79aa42eced36bfbe5b95629bd5e9fde4585/fonts/NotoSansMyanmar/hinted/ttf/NotoSansMyanmar-Regular.ttf
FONT_SHA256=fafce4db400bc0b214907ccdbfb0ad2f18a57bfefd08c8a571830b84088cf2fc
FF="${FFMPEG:-ffmpeg}"
mkdir -p "$OUT/fonts"

clip() { # out hue tone
  "$FF" -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=s=1080x1920:r=24:d=8,hue=h=$2" -f lavfi -i "sine=f=$3:d=8:sample_rate=48000" \
    -c:v libx264 -preset veryfast -crf 26 -pix_fmt yuv420p -profile:v high -c:a aac -b:a 128k -ac 2 -movflags +faststart -shortest "$1"
}
clip "$OUT/clip1.mp4" 0 330
clip "$OUT/clip2.mp4" 140 440
"$FF" -hide_banner -loglevel error -y \
  -f lavfi -i "aevalsrc=0.25*sin(2*PI*220*t)*(0.6+0.4*sin(2*PI*2*t))+0.15*sin(2*PI*277.2*t)+0.12*sin(2*PI*329.6*t):s=44100:d=20" \
  -ac 2 -c:a libmp3lame -b:a 128k "$OUT/song.mp3"

curl -sfL --retry 3 -o "$OUT/fonts/NotoSansMyanmar-Regular.ttf" "$FONT_URL"
echo "$FONT_SHA256  $OUT/fonts/NotoSansMyanmar-Regular.ttf" | sha256sum -c --quiet -

cat > "$OUT/lyrics.ass" <<'ASS'
[Script Info]
ScriptType: v4.00+
PlayResX: 1080
PlayResY: 1920
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Lyric,Noto Sans Myanmar,64,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,3,0,5,40,40,0,1
Style: Karaoke,Noto Sans Myanmar,64,&H0000D7FF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,3,0,5,40,40,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.00,0:00:08.00,Lyric,,0,0,0,,{\pos(540,860)\fad(300,300)}ကျွန်တော် မိုးရွာနေတဲ့ ညမှာ တစ်ယောက်တည်း
Dialogue: 0,0:00:00.00,0:00:08.00,Lyric,,0,0,0,,{\pos(540,980)\fad(300,300)}မြန်မာ စာလုံးဆင့် ကမ္ဘာ ကြွက် ညှိ ဉာဏ်
Dialogue: 0,0:00:08.00,0:00:16.00,Karaoke,,0,0,0,,{\pos(540,1200)\k100}ကျွန်တော် {\k100}မိုးရွာနေတဲ့ {\k100}ညမှာ {\k200}တစ်ယောက်တည်း
ASS
ls -l "$OUT" "$OUT/fonts"
