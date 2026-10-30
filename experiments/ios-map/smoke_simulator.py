#!/usr/bin/env python3
"""Boot the unsigned fixture app and require real red/green/blue Metal output."""
import json
import os
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
launch = run('xcrun', 'simctl', 'launch', '--terminate-running-process', identifier,
             'net.pocketmap.iosprobe', stdout=subprocess.PIPE, text=True)
pid = int(launch.stdout.rsplit(':', 1)[1].strip())

try:
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        # A screenshot of SpringBoard after an app crash must never count as a pass.
        # Simulator applications share the macOS process namespace. The iOS
        # runtime does not ship a kill utility; check the host process directly.
        os.kill(pid, 0)
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
    with (OUTPUT / 'app-log.txt').open('w') as log:
        subprocess.run(['xcrun', 'simctl', 'spawn', identifier, 'log', 'show', '--last', '5m',
                        '--predicate', 'process == "TrailIosProbe"', '--style', 'compact'],
                       stdout=log, stderr=subprocess.STDOUT, timeout=60, check=False)
