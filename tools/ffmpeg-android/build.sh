#!/usr/bin/env bash
# Builds ffmpeg + ffprobe for Android arm64-v8a from pinned sources (D-23):
#   FFmpeg with libx264, libass (freetype + fribidi + harfbuzz, no fontconfig), native aac, zlib (png).
# Executables are PIE against bionic; third-party libraries are linked statically (libc++ too), so the only
# dynamic dependencies are Android system libraries. Output ($OUT):
#   libffmpeg.so, libffprobe.so  — named lib*.so so the APK packs them into lib/arm64-v8a/ (exec from nativeLibraryDir)
#   BUILDINFO.txt                — versions, sources, hashes, configure line, sizes
#   licenses/                    — license texts of every component
# Needs: ANDROID_NDK_HOME (NDK $NDK_VERSION), curl, git, make, pkg-config, meson, ninja.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/versions.sh"
WORK="${WORK:-$PWD/build/ffmpeg-android}"
OUT="${OUT:-$WORK/out}"
JOBS="${JOBS:-$(nproc)}"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to NDK $NDK_VERSION}"
SRC="$WORK/src"; PREFIX="$WORK/prefix"; mkdir -p "$SRC" "$PREFIX" "$OUT/licenses"

TOOLS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
TRIPLE=aarch64-linux-android
export CC="$TOOLS/${TRIPLE}${ANDROID_API}-clang" CXX="$TOOLS/${TRIPLE}${ANDROID_API}-clang++"
export AR="$TOOLS/llvm-ar" RANLIB="$TOOLS/llvm-ranlib" STRIP="$TOOLS/llvm-strip" NM="$TOOLS/llvm-nm"
export CFLAGS="-O2 -fPIC" CXXFLAGS="-O2 -fPIC"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" PKG_CONFIG_PATH=""
grep -q "Pkg.Revision = $NDK_VERSION" "$NDK/source.properties" || echo "warning: NDK is not $NDK_VERSION ($(grep Pkg.Revision "$NDK/source.properties"))" >&2

fetch() { # name url sha256
  local f="$SRC/$(basename "$2")"
  [ -f "$f" ] || curl -sfL --retry 3 -o "$f" "$2"
  echo "$3  $f" | sha256sum -c --quiet - || { echo "checksum mismatch: $2" >&2; exit 1; }
  rm -rf "$SRC/$1"; mkdir -p "$SRC/$1"; tar -xf "$f" -C "$SRC/$1" --strip-components=1
}

cat > "$WORK/cross.meson" <<MESON
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'
[built-in options]
c_args = ['-O2', '-fPIC']
cpp_args = ['-O2', '-fPIC']
default_library = 'static'
prefix = '$PREFIX'
libdir = 'lib'
buildtype = 'release'
MESON
meson_build() { # dir options...
  local d="$1"; shift
  (cd "$SRC/$d" && meson setup _b --cross-file "$WORK/cross.meson" -Dpkg_config_path="$PREFIX/lib/pkgconfig" "$@" > "$WORK/$d.log" 2>&1 \
    && ninja -C _b -j "$JOBS" install >> "$WORK/$d.log" 2>&1) || { tail -n 40 "$WORK/$d.log" >&2; exit 1; }
}
log() { echo "== $*"; }

log "x264 $X264_COMMIT"
rm -rf "$SRC/x264"; git clone -q "$X264_GIT" "$SRC/x264"; git -C "$SRC/x264" -c advice.detachedHead=false checkout -q "$X264_COMMIT"
(cd "$SRC/x264" && ./configure --prefix="$PREFIX" --host="$TRIPLE" --cross-prefix="$TOOLS/llvm-" --sysroot="$SYSROOT" \
  --enable-static --enable-pic --disable-cli --disable-opencl --extra-cflags="-O2" > "$WORK/x264.log" 2>&1 \
  && make -j "$JOBS" install-lib-static >> "$WORK/x264.log" 2>&1) || { tail -n 40 "$WORK/x264.log" >&2; exit 1; }

log "freetype $FREETYPE_VERSION"
fetch freetype "$FREETYPE_URL" "$FREETYPE_SHA256"
meson_build freetype -Dharfbuzz=disabled -Dpng=disabled -Dbzip2=disabled -Dbrotli=disabled -Dzlib=internal -Dtests=disabled

log "fribidi $FRIBIDI_VERSION"
fetch fribidi "$FRIBIDI_URL" "$FRIBIDI_SHA256"
meson_build fribidi -Ddocs=false -Dbin=false -Dtests=false

log "harfbuzz $HARFBUZZ_VERSION"
fetch harfbuzz "$HARFBUZZ_URL" "$HARFBUZZ_SHA256"
meson_build harfbuzz -Dfreetype=enabled -Dglib=disabled -Dgobject=disabled -Dcairo=disabled -Dicu=disabled -Dchafa=disabled \
  -Dpng=disabled -Dzlib=disabled -Draster=disabled -Dvector=disabled -Dgpu=disabled -Dgpu_demo=disabled -Dsubset=disabled \
  -Dtests=disabled -Ddocs=disabled -Dutilities=disabled -Dbenchmark=disabled -Dintrospection=disabled

log "libass $LIBASS_VERSION"
fetch libass "$LIBASS_URL" "$LIBASS_SHA256"
(cd "$SRC/libass" && ./configure --prefix="$PREFIX" --host="$TRIPLE" --enable-static --disable-shared --with-pic \
  --disable-fontconfig --disable-require-system-font-provider --disable-libunibreak > "$WORK/libass.log" 2>&1 \
  && make -j "$JOBS" install >> "$WORK/libass.log" 2>&1) || { tail -n 40 "$WORK/libass.log" >&2; exit 1; }

log "ffmpeg $FFMPEG_VERSION"
fetch ffmpeg "$FFMPEG_URL" "$FFMPEG_SHA256"
FF_CONFIG=(
  --prefix="$PREFIX" --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile
  --cc="$CC" --cxx="$CXX" --ld="$CXX" --ar="$AR" --nm="$NM" --ranlib="$RANLIB" --strip="$STRIP" --sysroot="$SYSROOT"
  --pkg-config=pkg-config --pkg-config-flags=--static
  --enable-gpl --enable-libx264 --enable-libass --enable-zlib --enable-pthreads
  --disable-autodetect --disable-shared --enable-static --enable-pic
  --disable-doc --disable-ffplay --disable-network --disable-debug --disable-indevs --disable-outdevs
  --extra-cflags="-O2 -fPIC -I$PREFIX/include" --extra-ldflags="-L$PREFIX/lib -pie -static-libstdc++"
)
(cd "$SRC/ffmpeg" && ./configure "${FF_CONFIG[@]}" > "$WORK/ffmpeg.log" 2>&1 && make -j "$JOBS" >> "$WORK/ffmpeg.log" 2>&1) \
  || { tail -n 60 "$WORK/ffmpeg.log" >&2; tail -n 30 "$SRC/ffmpeg/ffbuild/config.log" >&2 || true; exit 1; }

"$STRIP" -o "$OUT/libffmpeg.so" "$SRC/ffmpeg/ffmpeg"
"$STRIP" -o "$OUT/libffprobe.so" "$SRC/ffmpeg/ffprobe"
chmod 755 "$OUT/libffmpeg.so" "$OUT/libffprobe.so"

cp "$SRC/ffmpeg/COPYING.GPLv2" "$OUT/licenses/ffmpeg-COPYING.GPLv2"
cp "$SRC/ffmpeg/LICENSE.md" "$OUT/licenses/ffmpeg-LICENSE.md"
cp "$SRC/x264/COPYING" "$OUT/licenses/x264-COPYING"
cp "$SRC/libass/COPYING" "$OUT/licenses/libass-COPYING"
cp "$SRC/fribidi/COPYING" "$OUT/licenses/fribidi-COPYING"
cp "$SRC/harfbuzz/COPYING" "$OUT/licenses/harfbuzz-COPYING"
cp "$SRC/freetype/docs/FTL.TXT" "$OUT/licenses/freetype-FTL.TXT"

needed() { "$TOOLS/llvm-readelf" -d "$1" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr '\n' ' '; }
{
  echo "Auto Flow bundled ffmpeg (D-23) — arm64-v8a, Android API $ANDROID_API, NDK $(sed -n 's/Pkg.Revision = //p' "$NDK/source.properties")"
  echo "License: GPL-2.0-or-later (built with --enable-gpl and libx264); texts in licenses/"
  echo "ffmpeg   $FFMPEG_VERSION  $FFMPEG_URL  sha256 $FFMPEG_SHA256"
  echo "x264     $X264_COMMIT  $X264_GIT (stable)"
  echo "libass   $LIBASS_VERSION  $LIBASS_URL  sha256 $LIBASS_SHA256"
  echo "freetype $FREETYPE_VERSION  $FREETYPE_URL  sha256 $FREETYPE_SHA256"
  echo "fribidi  $FRIBIDI_VERSION  $FRIBIDI_URL  sha256 $FRIBIDI_SHA256"
  echo "harfbuzz $HARFBUZZ_VERSION  $HARFBUZZ_URL  sha256 $HARFBUZZ_SHA256"
  echo "configure: ${FF_CONFIG[*]}" | sed "s|$NDK|\$NDK|g; s|$PREFIX|\$PREFIX|g"
  for f in libffmpeg.so libffprobe.so; do
    echo "$f  $(stat -c %s "$OUT/$f") bytes  sha256 $(sha256sum "$OUT/$f" | cut -d' ' -f1)  needs: $(needed "$OUT/$f")"
  done
} > "$OUT/BUILDINFO.txt"
cat "$OUT/BUILDINFO.txt"
