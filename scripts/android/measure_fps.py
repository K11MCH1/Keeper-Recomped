"""Measure the game's real presentation rate from SurfaceFlinger.

Independent of the engine's own counter: reads the actual present timestamps
of the game's surface layer (`dumpsys SurfaceFlinger --latency`).

  python scripts/android/measure_fps.py [--package org.keeperrecomped.app] [--seconds 10]
"""
import argparse
import os
import re
import statistics
import subprocess
import time

PENDING = (1 << 63) - 1  # SurfaceFlinger's marker for "not presented yet"


def adb(serial, *args):
    env = dict(os.environ, MSYS_NO_PATHCONV='1')
    cmd = ['adb'] + (['-s', serial] if serial else []) + ['shell', *args]
    return subprocess.run(cmd, capture_output=True, text=True, env=env, errors='replace').stdout


def find_layer(serial, package):
    names = []
    for line in adb(serial, 'dumpsys', 'SurfaceFlinger', '--list').splitlines():
        line = line.strip()
        # Android 15+: "RequestedLayerState{<name> parentId=... z=...}"; older: the bare name.
        m = re.match(r'RequestedLayerState\{(.*?)(?: parentId=.*)?\}$', line)
        names.append(m.group(1) if m else line)
    # SDL draws into a SurfaceView; its BLAST layer carries the game's buffers.
    candidates = [n for n in names if f'SurfaceView[{package}/' in n and 'GameActivity' in n
                  and not n.startswith('Background for')]
    candidates.sort(key=lambda n: ('(BLAST)' not in n, len(n)))
    return candidates[0] if candidates else None


def sample(serial, layer):
    out = adb(serial, 'dumpsys', 'SurfaceFlinger', '--latency', f'"{layer}"').split('\n')
    period = int(out[0]) if out and out[0].strip().isdigit() else 0
    presents = []
    for line in out[1:]:
        parts = line.split()
        if len(parts) == 3 and parts[1].isdigit():
            t = int(parts[1])
            if 0 < t < PENDING:
                presents.append(t)
    return period, presents


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--serial', default='9125335101BA')
    p.add_argument('--package', default='org.keeperrecomped.app')
    p.add_argument('--seconds', type=float, default=10)
    a = p.parse_args()
    layer = find_layer(a.serial, a.package)
    if not layer:
        print('No game surface layer found; is the game in the foreground?')
        return 1
    seen, period = set(), 0
    end = time.time() + a.seconds
    while time.time() < end:
        adb(a.serial, 'dumpsys', 'SurfaceFlinger', '--latency-clear', f'"{layer}"')
        time.sleep(0.8)  # SurfaceFlinger keeps the last 128 frames
        period, presents = sample(a.serial, layer)
        seen.update(presents)
    times = sorted(seen)
    if len(times) < 3:
        print(f'layer {layer}: too few frames ({len(times)})')
        return 1
    deltas = [(b - c) / 1e6 for c, b in zip(times, times[1:]) if 0 < b - c < 500e6]
    fps = 1000 / statistics.mean(deltas)
    q = statistics.quantiles(deltas, n=100)
    refresh = 1e9 / period if period else 0
    print(f'layer: {layer}')
    print(f'display refresh: {refresh:.1f} Hz; frames: {len(times)}')
    print(f'present rate: {fps:.1f} FPS; frame time ms p50 {q[49]:.2f}, p95 {q[94]:.2f}, p99 {q[98]:.2f}, max {max(deltas):.2f}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
