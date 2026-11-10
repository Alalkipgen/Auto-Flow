# Pinned sources for the bundled Android ffmpeg (D-23). Changing anything here rebuilds the binary in CI.
NDK_VERSION=29.0.14206865
ANDROID_API=26

FFMPEG_VERSION=9.0.2
FFMPEG_URL=https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz
FFMPEG_SHA256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e

# x264 has no release tarballs: the `stable` branch commit is the pin (git verifies it).
X264_GIT=https://code.videolan.org/videolan/x264.git
X264_COMMIT=b35605ace3ddf7c1a5d67a2eb553f034aef41d55

FREETYPE_VERSION=2.15.0
FREETYPE_URL=https://download.savannah.gnu.org/releases/freetype/freetype-${FREETYPE_VERSION}.tar.xz
FREETYPE_SHA256=93cd47eb719c325107f2d3cf087d06299e944c3b48a40b55537302304dea1dd5

FRIBIDI_VERSION=1.0.18
FRIBIDI_URL=https://github.com/fribidi/fribidi/releases/download/v${FRIBIDI_VERSION}/fribidi-${FRIBIDI_VERSION}.tar.xz
FRIBIDI_SHA256=1de5e8073d035388b71d0b2d452b1da1f8caeea8a7702997b401fe1b6250727f

HARFBUZZ_VERSION=15.1.0
HARFBUZZ_URL=https://github.com/harfbuzz/harfbuzz/releases/download/${HARFBUZZ_VERSION}/harfbuzz-${HARFBUZZ_VERSION}.tar.xz
HARFBUZZ_SHA256=0fc7c565443a5f4be7da65ac5ee5bd737d9b158dc908708b7689a0a5796b0c8d

LIBASS_VERSION=0.17.5
LIBASS_URL=https://github.com/libass/libass/releases/download/${LIBASS_VERSION}/libass-${LIBASS_VERSION}.tar.xz
LIBASS_SHA256=2dca25c0e0c837ddf00b52011b3f82cac1e4ddd3ad018227806b0c2288864acc
