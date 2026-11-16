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
    for case in ET.parse(path).getroot().iter('testcase'):
        row['tests'] += 1
        row['failures'] += int(case.find('failure') is not None or case.find('error') is not None)
        row['skipped'] += int(case.find('skipped') is not None)

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
