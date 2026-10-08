#!/usr/bin/env python3
"""Offline resource and lexical checks only; not an Android/Kotlin compiler."""
from pathlib import Path
from collections import Counter
import json
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'common/src/main/res'
errors = []
xml_files = list(RES.rglob('*.xml'))
for path in xml_files:
    try:
        ET.parse(path)
    except ET.ParseError as problem:
        errors.append(f'{path.relative_to(ROOT)}: {problem}')

def resources(folder):
    return [(node.tag, node.attrib.get('name'))
            for path in folder.glob('*.xml') for node in ET.parse(path).getroot()
            if node.attrib.get('name')]

for folder in RES.glob('values*'):
    duplicates = [name for name, count in Counter(resources(folder)).items() if count > 1]
    if duplicates:
        errors.append(f'{folder.relative_to(ROOT)}: duplicate resources {duplicates}')
base_strings = {name for kind, name in resources(RES / 'values') if kind == 'string'}
for path in [RES / 'values/steam_settings.xml', RES / 'values-zh-rCN/steam_settings.xml']:
    if not path.exists():
        errors.append(f'Missing {path}')
        continue
    strings = {node.attrib['name']: node.text or '' for node in ET.parse(path).getroot()}
    if not set(strings).issubset(base_strings):
        errors.append(f'{path}: strings missing from base locale')
base = {node.attrib['name']: node.text or '' for node in ET.parse(RES / 'values/steam_settings.xml').getroot()}
zh = {node.attrib['name']: node.text or '' for node in ET.parse(RES / 'values-zh-rCN/steam_settings.xml').getroot()}
for name, value in base.items():
    if name not in zh or re.findall(r'%\d+\$[ds]', value) != re.findall(r'%\d+\$[ds]', zh.get(name, '')):
        errors.append(f'Locale placeholder mismatch: {name}')

# Remove comments/literals before balancing delimiters. This deliberately does not
# claim to type-check Kotlin or validate expressions in interpolated strings.
def delimiters(text, filename):
    stack = []
    index = 0
    while index < len(text):
        if text.startswith('//', index):
            stop = text.find('\n', index)
            index = len(text) if stop < 0 else stop + 1
            continue
        if text.startswith('/*', index):
            depth = 1
            index += 2
            while index < len(text) and depth:
                if text.startswith('/*', index):
                    depth += 1
                    index += 2
                elif text.startswith('*/', index):
                    depth -= 1
                    index += 2
                else:
                    index += 1
            if depth:
                errors.append(f'{filename}: unclosed comment')
            continue
        if text.startswith('"""', index):
            stop = text.find('"""', index + 3)
            if stop < 0:
                errors.append(f'{filename}: unclosed multiline string')
                return
            index = stop + 3
            continue
        if text[index] in ['"', "'", '`']:
            quote = text[index]
            index += 1
            while index < len(text) and text[index] != quote:
                index += 2 if text[index] == '\\' and quote != '`' else 1
            if index >= len(text):
                errors.append(f'{filename}: unclosed literal')
                return
            index += 1
            continue
        char = text[index]
        if char in '([{':
            stack.append((char, index))
        elif char in ')]}':
            if not stack or stack[-1][0] != dict(zip(')]}', '([{'))[char]:
                errors.append(f'{filename}: unmatched {char} at offset {index}')
                return
            stack.pop()
        index += 1
    if stack:
        errors.append(f'{filename}: unclosed delimiters {stack}')

changed = [ROOT / relative for relative in [
    'common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt',
    'common/src/main/java/com/shilapi/xcertplay/SteamGlass.kt',
    'common/src/test/java/com/shilapi/xcertplay/CarPlayCustomizationTest.kt',
    'common/src/test/java/com/shilapi/xcertplay/LocationReportingSettingsTest.kt',
    'common/src/test/java/com/shilapi/xcertplay/BydVehicleDataSettingsTest.kt',
    'common/src/test/java/com/shilapi/xcertplay/BydSettingsReconnectTest.kt',
    'common/src/test/java/com/shilapi/xcertplay/SteamGlassSettingsTest.kt',
    'mobile/build.gradle.kts',
    'common/src/main/java/com/shilapi/xcertplay/AudioChannelPreview.kt',
    'common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt',
    'common/src/main/java/com/shilapi/xcertplay/LocationPanel.java',
    'shared/src/main/java/com/shilapi/xcertplay/media/VideoDecodeQueue.kt',
    'shared/src/main/java/com/shilapi/xcertplay/media/BoundedVideoJobQueue.java',
]]
for path in changed:
    text = path.read_text()
    delimiters(text, path.relative_to(ROOT))
    for name in set(re.findall(r'R\.string\.(steam_\w+)', text)):
        if name not in base_strings:
            errors.append(f'{path}: missing {name}')
result = {
    'version': re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT / 'mobile/build.gradle.kts').read_text()).group(1),
    'check_type': 'offline_resource_and_lexical_checks_only',
    'resource_xml_files': len(xml_files),
    'source_files_checked': len(changed),
    'kotlin_files_checked': sum(path.suffix in ['.kt', '.kts'] for path in changed),
    'java_files_checked': sum(path.suffix == '.java' for path in changed),
    'errors': errors,
    'status': 'passed' if not errors else 'failed',
    'kotlin_compilation_performed': False,
    'android_tests_executed': False,
}
print(json.dumps(result, ensure_ascii=False, indent=2))
sys.exit(bool(errors))
