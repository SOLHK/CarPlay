#!/usr/bin/env python3
"""Compile and exercise the production queue core without an Android SDK."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='carplay-video-queue-') as output:
    subprocess.run([
        'java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '--release', '11', '-d', output,
        str(ROOT / 'shared/src/main/java/com/shilapi/xcertplay/media/BoundedVideoJobQueue.java'),
        str(ROOT / 'tools/VideoQueueStressCheck.java'),
    ], check=True, timeout=30)
    subprocess.run([
        'java', '-ea', '-Xmx128m', '-cp', output,
        'com.shilapi.xcertplay.media.VideoQueueStressCheck',
    ], check=True, timeout=30)
