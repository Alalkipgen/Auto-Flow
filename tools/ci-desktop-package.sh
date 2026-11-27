#!/usr/bin/env bash
# D-61: package the real desktop app image and retain evidence even if jlink/jpackage fails.
set -euo pipefail
out="${SMOKE_OUT:?Set SMOKE_OUT to the desktop smoke evidence directory}"
mkdir -p "$out"

package_desktop() {
    local log="$1"
    shift
    bash gradlew :app-desktop:createDistributable --console=plain --no-build-cache --max-workers=2 \
        '-Dorg.gradle.jvmargs=-Xmx1600m -Dfile.encoding=UTF-8' \
        -Pkotlin.compiler.execution.strategy=in-process "$@" 2>&1 | tee "$log"
}

status=0
(
    printf 'Source: %s\n' "$(git rev-parse HEAD)"
    jdk="${JAVA_HOME:?Desktop packaging requires a full JDK}"
    "$jdk/bin/java" -version || exit
    "$jdk/bin/jpackage" --version || exit
    if [[ ! -x "$jdk/bin/jlink" || ! -r "$jdk/jmods/java.base.jmod" ]]; then
        echo 'Desktop packaging requires a full JDK with jlink and jmods/java.base.jmod.'
        exit 1
    fi
    first="$out/package-attempt-1.log"
    if package_desktop "$first"; then
        exit 0
    else
        package_status=$?
    fi
    # A runner can briefly miss a published POM. Clear negative dependency metadata once;
    # never retry compilation, jlink or jpackage failures, or change the pinned dependencies.
    if grep -Fq "Could not resolve all files for configuration ':app-desktop:runtimeClasspath'" "$first" &&
        grep -Fq 'https://repo.maven.apache.org/maven2/' "$first" &&
        grep -Eq 'Could not find [^[:space:]]+:[^[:space:]]+:[^[:space:]]+|Could not (GET|HEAD) ' "$first"; then
        printf '%s\n' '::warning title=Desktop packaging dependency resolution::Maven Central resolution failed; retrying the package once. See package-attempt-1.log.'
        sleep 5
        package_desktop "$out/package-attempt-2.log" --refresh-dependencies
    else
        exit "$package_status"
    fi
) 2>&1 | tee "$out/package.log" || status=$?

if (( status != 0 )); then
    if [[ -d app-desktop/build/compose/logs ]]; then
        cp -R app-desktop/build/compose/logs "$out/compose-logs"
    fi
    # Public checks should show the Gradle error even when raw job logs require sign-in.
    why="$(awk '/^\* What went wrong:/{block=""; show=1; next} /^\* Try:/{show=0} show && NF{block=block $0 "\n"} END{printf "%s", block}' "$out/package.log")"
    native="$(find "$out/compose-logs" -type f -name '*-err.txt' -size +0c -exec head -n 4 {} \; 2>/dev/null || true)"
    why="${why:-$(tail -n 10 "$out/package.log")}"
    why="${why:0:1300}${native:+$'\n'${native:0:900}}"
    why="${why//%/%25}"; why="${why//$'\r'/%0D}"; why="${why//$'\n'/%0A}"
    printf '::error title=Desktop packaging failed::%s\n' "$why"
    exit "$status"
fi
