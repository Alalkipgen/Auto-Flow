#!/usr/bin/env bash
# D-61: package the real desktop app image and retain evidence even if jlink/jpackage fails.
set -euo pipefail
out="${SMOKE_OUT:?Set SMOKE_OUT to the desktop smoke evidence directory}"
mkdir -p "$out"

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
    bash gradlew :app-desktop:createDistributable --console=plain --no-build-cache --max-workers=2 \
        '-Dorg.gradle.jvmargs=-Xmx1600m -Dfile.encoding=UTF-8' \
        -Pkotlin.compiler.execution.strategy=in-process
) 2>&1 | tee "$out/package.log" || status=$?

if (( status != 0 )); then
    if [[ -d app-desktop/build/compose/logs ]]; then
        cp -R app-desktop/build/compose/logs "$out/compose-logs"
    fi
    # Public checks should show the Gradle error even when raw job logs require sign-in.
    why="$(awk '/^\* What went wrong:/{show=1; next} /^\* Try:/{exit} show && NF' "$out/package.log")"
    native="$(find "$out/compose-logs" -type f -name '*-err.txt' -size +0c -exec head -n 4 {} \; 2>/dev/null || true)"
    why="${why:-$(tail -n 10 "$out/package.log")}"
    why="${why:0:1300}${native:+$'\n'${native:0:900}}"
    why="${why//%/%25}"; why="${why//$'\r'/%0D}"; why="${why//$'\n'/%0A}"
    printf '::error title=Desktop packaging failed::%s\n' "$why"
    exit "$status"
fi
