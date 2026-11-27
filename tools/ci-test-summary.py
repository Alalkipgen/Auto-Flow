#!/usr/bin/env python3
"""Publish fresh JUnit case counts; skips are not passes. No test status is changed here."""
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET

rows = {}
for path in sorted(Path('.').glob('*/build/test-results/**/*.xml')):
    parts = path.parts
    key = f'{parts[0]}/{parts[3]}'
    row = rows.setdefault(key, dict(tests=0, failures=0, skipped=0))
    root = ET.parse(path).getroot()
    failed = False
    def escape(value):
        return value.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    for case in root.iter('testcase'):
        row['tests'] += 1
        failure = case.find('failure')
        if failure is None:
            failure = case.find('error')
        row['failures'] += int(failure is not None)
        row['skipped'] += int(case.find('skipped') is not None)
        if failure is not None:
            failed = True
            identity = f"{case.get('classname', key)}.{case.get('name', '?')}"
            detail = f"{identity}\n{failure.get('message', '')}\n{failure.text or ''}"[:6000]
            print(f'::error title=JUnit failure::{escape(detail)}')
    if failed:
        # Publish bounded synthetic runtime diagnostics alongside the failure. Artifact/log downloads
        # need sign-in; check annotations expose the assertion without silently re-running it to green.
        diagnostics = [line for line in (root.findtext('system-out') or '').splitlines()
                       if line.startswith(('LIVE_', 'PLAYBACK_'))]
        if diagnostics:
            print(f"::notice title=Runtime failure diagnostics::{escape(chr(10).join(diagnostics[-48:])[:6000])}")

out = Path('build/qa')
out.mkdir(parents=True, exist_ok=True)
(out / 'test-summary.json').write_text(json.dumps(dict(source=os.getenv('GITHUB_SHA', 'local'), groups=rows), indent=2) + '\n')
lines = ['| Test group | Cases | Passed | Failures/errors | Skipped/not executed |', '| --- | ---: | ---: | ---: | ---: |']
for key, row in rows.items():
    passed = row['tests'] - row['failures'] - row['skipped']
    message = f"{key}: {row['tests']} cases, {passed} passed, {row['failures']} failures, {row['skipped']} skipped"
    print(f'::notice title=JUnit counts::{message}')
    lines.append(f"| {key} | {row['tests']} | {passed} | {row['failures']} | {row['skipped']} |")
if os.getenv('GITHUB_STEP_SUMMARY'):
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
        stream.write('\n'.join(lines) + '\n\nRuntime tests skipped here require the separate real-libVLC job; a skip is not playback evidence.\n')
if not rows:
    print('::warning::No JUnit XML found; no executed-test count is claimed')
