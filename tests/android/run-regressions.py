#!/usr/bin/env python3
"""Install only into a named fresh disposable emulator, then check instrumentation results."""
import argparse
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--avd', required=True)
parser.add_argument('--install', action='store_true')
args = parser.parse_args()
assert args.serial.startswith('emulator-'), 'Physical devices are forbidden'
adb = os.environ.get('ADB', 'adb')
base = [adb, '-s', args.serial]


def run(*command):
    return subprocess.check_output(base + list(command), text=True, timeout=240).strip()


assert run('shell', 'getprop', 'ro.hardware') in ('ranchu', 'goldfish')
assert run('shell', 'getprop', 'ro.boot.qemu.avd_name') == args.avd
assert run('shell', 'getprop', 'sys.boot_completed') == '1'
if args.install:
    # First installs only: replacement installs require device recording/save checks.
    for package in ['org.diy.liferecorder', 'org.diy.liferecorder.integration']:
        assert 'package:' + package not in run('shell', 'pm', 'list', 'packages', package), 'Use a fresh emulator; existing install refused'
    for apk in ['outputs/life-recorder-phone-0.5.4-debug.apk', 'work/phone-tests-v2/tests.apk']:
        result = run('install', apk)
        assert 'Success' in result, result

out = Path('work/process-optimization/results')
out.mkdir(parents=True, exist_ok=True)
for name in ['WorkflowTests', 'HourlySummaryTests', 'ExportTests']:
    result = run('shell', 'am', 'instrument', '-w',
                 'org.diy.liferecorder.integration/org.diy.liferecorder.' + name)
    (out / (name + '.log')).write_text(result)
    print(name + '\n' + result, flush=True)
    assert 'INSTRUMENTATION_CODE: -1' in result and 'INSTRUMENTATION_RESULT: error=' not in result, result
for phase in ['agent-pipeline', 'understanding052', 'search-memory053', 'detail-playback053']:
    result = run('shell', 'am', 'instrument', '-w', '-e', 'phase', phase,
                 'org.diy.liferecorder.integration/org.diy.liferecorder.IntegrationTests')
    (out / (phase + '.log')).write_text(result)
    print(phase + '\n' + result, flush=True)
    assert 'INSTRUMENTATION_CODE: -1' in result and 'INSTRUMENTATION_RESULT: error=' not in result, result
