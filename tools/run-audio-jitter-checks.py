#!/usr/bin/env python3
"""Run the audio policy regression tests without an Android SDK.

Supply a directory containing Kotlin compiler-embeddable 2.2.10 and its runtime
jars, junit 4.13.2, hamcrest-core 1.3, annotations 13.0 and an Android API jar.
The Android jar is used only for AudioManager constants; no device is simulated.
"""
import argparse
from pathlib import Path
import os
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jars', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
classpath = os.pathsep.join(str(p.resolve()) for p in sorted(args.jars.glob('*.jar')))
if not classpath:
    parser.error('No jar files found')
production = ['MediaAudioBuffer', 'AudioBufferProgress', 'AudioChannelMapping', 'AudioTailDrain']
tests = production + ['AudioJitterPlayback']
base = root / 'shared/src'
sources = [base / f'main/java/com/shilapi/xcertplay/media/{name}.kt' for name in production]
sources += [base / f'test/java/com/shilapi/xcertplay/media/{name}Test.kt' for name in tests]
with tempfile.TemporaryDirectory(prefix='carplay-audio-check-') as output:
    subprocess.run(['java', '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '11', '-classpath', classpath,
                    '-d', output, *map(str, sources)], check=True, timeout=60)
    subprocess.run(['java', '-cp', classpath + os.pathsep + output,
                    'org.junit.runner.JUnitCore',
                    *[f'com.shilapi.xcertplay.media.{name}Test' for name in tests]],
                   check=True, timeout=30)
