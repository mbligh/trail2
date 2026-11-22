#!/usr/bin/env python3
"""Boot the unsigned fixture app and require real red/green/blue Metal output."""
import json
import shutil
from pathlib import Path
import subprocess
import time
from PIL import Image

ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / 'build/smoke'
OUTPUT.mkdir(parents=True, exist_ok=True)


def run(*args, **kwargs):
    return subprocess.run(args, check=True, timeout=180, **kwargs)


devices = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'devices', 'available', '--json'], timeout=60))
phones = [device for runtime, values in devices['devices'].items() if '.iOS-' in runtime
          for device in values if 'iPhone' in device['name'] and device.get('isAvailable')]
if not phones:
    raise RuntimeError('No available iPhone simulator')
phone = next((device for device in phones if device['state'] == 'Booted'), phones[0])
identifier = phone['udid']
if phone['state'] != 'Booted':
    run('xcrun', 'simctl', 'boot', identifier)
run('xcrun', 'simctl', 'bootstatus', identifier, '-b')
run('xcrun', 'simctl', 'status_bar', identifier, 'override', '--time', '09:41')
run('xcrun', 'simctl', 'install', identifier, str(ROOT / 'build/TrailIosProbe.app'))
console_path = OUTPUT / 'console.txt'
console = console_path.open('w')
launch = subprocess.Popen(
    ['xcrun', 'simctl', 'launch', '--terminate-running-process', '--console',
     identifier, 'net.pocketmap.iosprobe', '--metal-probe'], stdout=console, stderr=subprocess.STDOUT)

try:
    # --console remains attached for the app lifetime, but does not promise a
    # PID banner. Monitor it directly and require actual fixture pixels below.
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        # A screenshot of SpringBoard after an app crash must never count as a pass.
        if launch.poll() is not None:
            raise RuntimeError('iOS probe exited before rendering; see console.txt')
        screenshot = OUTPUT / 'ios-metal.png'
        run('xcrun', 'simctl', 'io', identifier, 'screenshot', str(screenshot))
        image = Image.open(screenshot).convert('RGB')
        # Exclude the status/header/home indicator; all counted colors belong to the map.
        pixels = list(image.crop((0, image.height // 5, image.width, image.height * 4 // 5)).getdata())
        counts = {
            'white_background': sum(r > 245 and g > 245 and b > 245 for r, g, b in pixels),
            'trail_red': sum(r > 210 and g < 70 and b < 70 for r, g, b in pixels),
            'area_green': sum(g > r + 30 and g > b + 30 for r, g, b in pixels),
            'recording_blue': sum(b > r + 50 and b > g + 35 for r, g, b in pixels),
        }
        (OUTPUT / 'render-counts.json').write_text(json.dumps(counts, indent=2) + '\n')
        if counts['white_background'] > len(pixels) // 5 and counts['trail_red'] >= 150 and counts['area_green'] >= 1000 and counts['recording_blue'] >= 150:
            print('Shared map content rendered through iOS Metal:', counts)
            break
        time.sleep(3)
    else:
        raise AssertionError(f'Missing shared map content: {counts}')
finally:
    # Collect diagnostics BEFORE cleanup: simctl terminate can hang when the app
    # exits immediately or SpringBoard never brings it to the foreground.
    # The --console process can remain open after the actual app has died.
    with (OUTPUT / 'app-log.txt').open('w') as log:
        try:
            subprocess.run(
                ['xcrun', 'simctl', 'spawn', identifier, 'log', 'show', '--last', '12m',
                 '--style', 'compact', '--predicate',
                 'process == "TrailIosProbe" OR eventMessage CONTAINS[c] "iosprobe"'],
                stdout=log, stderr=subprocess.STDOUT, timeout=75, check=False,
            )
        except subprocess.TimeoutExpired:
            log.write('simctl log show timed out\n')
    reports = []
    for directory in (Path.home() / 'Library/Logs/DiagnosticReports',
                      Path.home() / 'Library/Developer/CoreSimulator/Devices' /
                      identifier / 'data/Library/Logs/DiagnosticReports'):
        if directory.exists():
            reports.extend(directory.glob('TrailIosProbe*'))
    for report in sorted(reports, key=lambda path: path.stat().st_mtime)[-4:]:
        shutil.copy2(report, OUTPUT / report.name)
    try:
        subprocess.run(['xcrun', 'simctl', 'terminate', identifier, 'net.pocketmap.iosprobe'],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       timeout=15, check=False)
    except subprocess.TimeoutExpired:
        print('WARNING: simctl terminate hung; preserving original test failure', flush=True)
    try:
        launch.wait(timeout=10)
    except subprocess.TimeoutExpired:
        launch.terminate()
        try:
            launch.wait(timeout=5)
        except subprocess.TimeoutExpired:
            launch.kill()
            launch.wait(timeout=5)
    console.close()
    print('IOS_CONSOLE_BEGIN\n' + console_path.read_text(errors='replace')[-40000:] +
          '\nIOS_CONSOLE_END', flush=True)
    app_log = (OUTPUT / 'app-log.txt').read_text(errors='replace')
    print('IOS_SYSTEM_LOG_BEGIN\n' + app_log[-50000:] + '\nIOS_SYSTEM_LOG_END',
          flush=True)
