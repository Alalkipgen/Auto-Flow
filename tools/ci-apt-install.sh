#!/usr/bin/env bash
# CI only: apt-get install with bounded time. On 2026-11-11 an apt mirror hung the android job for 90 min;
# now each try is capped at 4 min, apt itself retries slow downloads, and 3 tries fail the step clearly.
set -u
opts=(-o Acquire::Retries=3 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30)
for i in 1 2 3; do
  if sudo timeout 240 apt-get "${opts[@]}" update -qq \
    && sudo timeout 240 apt-get "${opts[@]}" install -y -qq --no-install-recommends "$@" > /dev/null; then
    exit 0
  fi
  echo "::warning::apt-get try $i of 3 failed or timed out ($*)"
  sleep 15
done
echo "::error::apt-get could not install: $*"
exit 1
