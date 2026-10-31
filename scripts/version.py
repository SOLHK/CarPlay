#!/usr/bin/env python3
"""Bump app versions, or reject app changes that reuse a released version."""
import argparse
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("mobile", "automotive")
NAME = re.compile(r'^(\s*versionName\s*=\s*")([0-9]+\.[0-9]+\.[0-9]+)("\s*)$', re.MULTILINE)
CODE = re.compile(r'^(\s*versionCode\s*=\s*)([0-9]+)(\s*)$', re.MULTILINE)
BUILD_FILES = {"build.gradle.kts", "settings.gradle.kts", "gradle.properties",
               "common/build.gradle.kts", "shared/build.gradle"}


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def version(text):
    names, codes = list(NAME.finditer(text)), list(CODE.finditer(text))
    if len(names) != 1 or len(codes) != 1:
        raise ValueError("Expected exactly one numeric versionName and versionCode")
    name = tuple(map(int, names[0][2].split(".")))
    code = int(codes[0][2])
    if not 0 < code <= 2_100_000_000:
        raise ValueError("versionCode must be in Android's supported positive range")
    return name, code


def app_changed(module, paths):
    for path in paths:
        if path in BUILD_FILES or path.startswith("gradle/"):
            return True
        for part in (module, "common", "shared"):
            if path.startswith(f"{part}/src/") and not any(
                    path.startswith(f"{part}/src/{tests}/")
                    for tests in ("test", "androidTest", "testFixtures")):
                return True
            if path == f"{part}/build.gradle.kts":
                return True
    return False


def bump(modules):
    # Validate every selected file before writing any version.
    updates = []
    for module in modules:
        path = ROOT / module / "build.gradle.kts"
        text = path.read_text()
        name, code = version(text)
        if code == 2_100_000_000:
            raise ValueError(f"{module}: versionCode is exhausted")
        next_name = f"{name[0]}.{name[1]}.{name[2] + 1}"
        text = NAME.sub(lambda match: match[1] + next_name + match[3], text)
        text = CODE.sub(lambda match: match[1] + str(code + 1) + match[3], text)
        updates.append((path, text, f"{module}: {next_name} ({code + 1})"))
    for path, text, message in updates:
        path.write_text(text)
        print(message)


def check(base):
    # A branch's first push has no previous commit. Still validate both versions.
    initial = base == "0" * 40
    commit = None if initial else git("rev-parse", "--verify", "--end-of-options", f"{base}^{{commit}}")
    paths = [] if initial else git("diff", "--name-only", commit, "--").splitlines()
    failed = False
    for module in MODULES:
        path = f"{module}/build.gradle.kts"
        current_name, current_code = version((ROOT / path).read_text())
        if initial or not app_changed(module, paths):
            print(f"{module}: version valid; no app change requires a bump")
            continue
        previous_name, previous_code = version(git("show", f"{commit}:{path}"))
        if current_name <= previous_name or current_code <= previous_code:
            print(f"{module}: app changed but versionName/versionCode did not both increase. "
                  f"Run: python3 scripts/version.py bump --module {module}", file=sys.stderr)
            failed = True
        else:
            print(f"{module}: {'.'.join(map(str, current_name))} ({current_code}) > "
                  f"{'.'.join(map(str, previous_name))} ({previous_code})")
    return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    bump_parser = commands.add_parser("bump", help="Increment patch version and versionCode")
    bump_parser.add_argument("--module", choices=MODULES, action="append",
                             help="App to increment; defaults to both apps")
    check_parser = commands.add_parser("check", help="Compare changed apps with a Git base")
    check_parser.add_argument("--base", required=True, help="PR base commit or previous main HEAD")
    args = parser.parse_args()
    try:
        if args.command == "bump":
            bump(list(dict.fromkeys(args.module or MODULES)))
            return 0
        return check(args.base)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Version check failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
