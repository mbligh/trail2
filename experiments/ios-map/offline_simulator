#!/usr/bin/env python3
"""Require real bundled vector features on iOS, including a cold app relaunch."""
import json
from pathlib import Path
import subprocess
import time
from PIL import Image

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'build/smoke'
devices = json.loads(subprocess.check_output(
    ['xcrun', 'simctl', 'list', 'devices', 'available', '--json'], timeout=60))
phone = next(device for runtime, values in devices['devices'].items() if '.iOS-' in runtime
             for device in values if 'iPhone' in device['name'] and device['state'] == 'Booted')
identifier = phone['udid']

for attempt in ('first', 'cold'):
    output = OUT / f'offline-{attempt}-console.txt'
    with output.open('w') as log:
        app = subprocess.Popen(
            ['xcrun', 'simctl', 'launch', '--terminate-running-process', '--console', identifier,
             'net.pocketmap.iosprobe', '--offline-map-test'], stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if app.poll() is not None:
                    raise RuntimeError(f'iOS offline map exited during {attempt}')
                console = output.read_text(errors='replace')
                if 'IOS_OFFLINE_RESOURCE_ERROR:' in console:
                    raise AssertionError('Bundled map resource failed; see offline console')
                # The Kotlin marker is emitted only after querying actual
                # rendered features with the basemap's class property.
                if 'IOS_OFFLINE_RENDER_PASS:' in console and 'IOS_OFFLINE_TILE_HIT:' in console:
                    screenshot = OUT / f'offline-{attempt}.png'
                    subprocess.run(['xcrun', 'simctl', 'io', identifier, 'screenshot', str(screenshot)],
                                   check=True, timeout=60)
                    image = Image.open(screenshot).convert('RGB')
                    pixels = list(image.crop((0, image.height // 5, image.width, image.height * 4 // 5)).getdata())
                    assert sum(min(pixel) < 220 for pixel in pixels) > 1000, 'Offline map screenshot is blank'
                    print(f'iOS bundled vector map passed {attempt}', flush=True)
                    break
                time.sleep(2)
            else:
                raise AssertionError(f'No real bundled vector features during {attempt}')
        finally:
            subprocess.run(['xcrun', 'simctl', 'terminate', identifier, 'net.pocketmap.iosprobe'],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30, check=False)
            try:
                app.wait(timeout=15)
            except subprocess.TimeoutExpired:
                app.terminate()
                app.wait(timeout=15)
    print(output.read_text(errors='replace')[-40000:], flush=True)
