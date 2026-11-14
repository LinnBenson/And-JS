#!/usr/bin/env python3
"""Sign and verify release APKs; credentials stay outside the repository."""
import hashlib, os, pathlib, subprocess, zipfile, re
root = pathlib.Path(__file__).resolve().parents[1]
workspace = root.parent
sdk = pathlib.Path(os.environ.get('ANDROID_HOME', pathlib.Path.home() / 'Library/Android/sdk'))
tools = sdk / 'build-tools/35.0.0'
version = re.search(r'versionName\s*=\s*"([^"]+)"', (root/'app/build.gradle.kts').read_text()).group(1)
out = workspace / 'package'
out.mkdir(exist_ok=True)
key = workspace / 'key'
artifacts = []
for abi in ('arm64-v8a', 'armeabi-v7a'):
    source = root / f'app/build/outputs/apk/release/app-{abi}-release-unsigned.apk'
    target = out / f'And-JS-{version}-{abi}.apk'
    subprocess.run([str(tools/'apksigner'), 'sign', '--v4-signing-enabled', 'false', '--ks', str(key/'andjs-release.p12'), '--ks-key-alias', 'andjs', '--ks-pass', f'file:{key / "store-password.txt"}', '--out', str(target), str(source)], check=True)
    subprocess.run([str(tools/'apksigner'), 'verify', '--verbose', str(target)], check=True)
    subprocess.run([str(tools/'zipalign'), '-c', '-P', '16', '4', str(target)], check=True)
    artifacts.append(target)
checks = out/'SHA256SUMS.txt'
checks.write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in artifacts))
with zipfile.ZipFile(out/f'And-JS-{version}.zip', 'w', zipfile.ZIP_DEFLATED) as z:
    for p in [checks, *artifacts]: z.write(p, p.name)
print('Packaged', version)
