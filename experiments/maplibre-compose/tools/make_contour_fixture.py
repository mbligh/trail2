#!/usr/bin/env python3
"""Generate a tiny, deterministic PMTiles v3 fixture with synthetic contour lines.

For integration tests only. NOT real NJ elevation data.
"""
import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path
import struct


def varint(n):
    assert n >= 0
    result = bytearray()
    while n >= 128:
        result.append((n & 127) | 128)
        n >>= 7
    result.append(n)
    return bytes(result)


def field(number, wire, data):
    return varint(number * 8 + wire) + (varint(len(data)) + data if wire == 2 else varint(data))


def signed(n):
    return n * 2 if n >= 0 else (-n * 2 - 1)


def mvt():
    # Valid Mapbox Vector Tile: named "contours", with numeric elevation_ft.
    features = bytearray()
    for i, (height, row) in enumerate([(100, 350), (50, 1050), (20, 1750), (10, 2450), (200, 3350)]):
        geometry = (varint(9) + varint(signed(50)) + varint(signed(row)) +
                    varint(10) + varint(signed(3990)) + varint(0))
        tags = varint(0) + varint(i)
        feature = (field(1, 0, i + 1) + field(2, 2, tags) +
                   field(3, 0, 2) + field(4, 2, geometry))
        features += field(2, 2, feature)
    layer = field(1, 2, b"contours") + features + field(3, 2, b"elevation_ft")
    for height in (100, 50, 20, 10, 200):
        layer += field(4, 2, field(4, 0, height))
    layer += field(5, 0, 4096) + field(15, 0, 2)
    return field(3, 2, layer)


def tile_id(z, x, y):
    result = ((1 << (2 * z)) - 1) // 3
    for a in range(z - 1, -1, -1):
        s = 1 << a
        rx, ry = (x & s), (y & s)
        result += ((3 * rx) ^ ry) << a
        if ry == 0:
            if rx:
                x, y = s - 1 - x, s - 1 - y
            x, y = y, x
    return result


def make(output):
    lon, lat = -74.771, 40.692
    ids = set()
    for z in range(12, 17):
        n = 1 << z
        x = int((lon + 180.0) / 360.0 * n)
        y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
        for dx in range(-1, 2):
            for dy in range(-1, 2):
                ids.add(tile_id(z, x + dx, y + dy))
    ordered = sorted(ids)
    tile = gzip.compress(mvt(), mtime=0)
    offsets = [i * len(tile) for i in range(len(ordered))]
    directory = bytearray()
    directory += varint(len(ordered))
    previous = 0
    for identifier in ordered:
        directory += varint(identifier - previous)
        previous = identifier
    for _ in ordered:
        directory += varint(1)
    for _ in ordered:
        directory += varint(len(tile))
    for index, offset in enumerate(offsets):
        directory += varint(offset + 1 if index == 0 else 0)
    root = gzip.compress(directory, mtime=0)
    metadata = gzip.compress(json.dumps({
        "name": "Synthetic contour interoperability test (not NJ elevation data)",
        "vector_layers": [{"id": "contours", "fields": {"elevation_ft": "Number"}}]
    }, separators=(",", ":")).encode(), mtime=0)
    root_at = 127
    metadata_at = root_at + len(root)
    tiles_at = metadata_at + len(metadata)
    header = (b"PMTiles" + b"\x03" + struct.pack("<11Q",
        root_at, len(root), metadata_at, len(metadata), 0, 0,
        tiles_at, len(tile) * len(ordered), len(ordered), len(ordered), len(ordered)) +
        bytes((1, 2, 2, 1, 12, 16)) +
        struct.pack("<4i", -750000000, 400000000, -740000000, 420000000) +
        bytes((13,)) + struct.pack("<2i", -747710000, 406920000))
    assert len(header) == 127
    assert len(header) + len(root) < 16384
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(header + root + metadata + tile * len(ordered))
    # The standalone app needs the same contour layer definitions as Trail2.
    root_repo = Path(__file__).resolve().parents[3]
    config = (root_repo / "basemaps.json").read_bytes()
    config_target = output.parents[1] / "basemaps.json"
    config_target.write_bytes(config)
    manifest_path = output.parents[1] / "offline-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    manifest["resources"][output.relative_to(output.parents[1]).as_posix()] = hashlib.sha256(output.read_bytes()).hexdigest()
    manifest["resources"]["basemaps.json"] = hashlib.sha256(config).hexdigest()
    manifest_path.write_text(json.dumps(manifest, indent=2))
    print(f"Created {output}: {len(ordered)} synthetic PMTiles tiles, {output.stat().st_size} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    make(parser.parse_args().output)
