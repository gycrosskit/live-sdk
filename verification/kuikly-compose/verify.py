#!/usr/bin/env python3
"""真实 Compose runtime 执行生产 LiveVideo；仅 Renderer/View 边界使用替身。"""
import hashlib
import os
from pathlib import Path
import subprocess
from urllib.request import urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/host-migration/compose'
CACHE = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
OUT.mkdir(parents=True, exist_ok=True)


def artifact(group, name, version, suffix='.jar'):
    matches = list((CACHE / group / name / version).glob(f'*/{name}-{version}{suffix}'))
    if len(matches) != 1:
        raise SystemExit(f'需要已解析依赖 {group}:{name}:{version}，实际匹配 {len(matches)} 个')
    return matches[0]


version = '2.2.21-1.0.0'
stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', version)
annotations = artifact('org.jetbrains', 'annotations', '13.0')
compiler = [artifact('org.jetbrains.kotlin', name, version) for name in (
    'kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
compiler += [stdlib, annotations, artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
             artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0')]
plugin = artifact('org.jetbrains.kotlin', 'kotlin-compose-compiler-plugin-embeddable', version)
runtime = OUT / 'androidx-runtime-desktop-1.9.0.jar'
expected = 'ae657e4332fb72fa1b437ce5caa486c6b4a5e17588aaef0267aa92918bba7458'
if not runtime.exists():
    with urlopen('https://dl.google.com/dl/android/maven2/androidx/compose/runtime/'
                 'runtime-desktop/1.9.0/runtime-desktop-1.9.0.jar', timeout=30) as response:
        runtime.write_bytes(response.read())
if hashlib.sha256(runtime.read_bytes()).hexdigest() != expected:
    raise SystemExit('Compose runtime 摘要不匹配')
kuikly = OUT / 'core.jar'
with zipfile.ZipFile(artifact('com.tencent.kuikly-open', 'core-android', '2.29.1-2.0.21-ohos', '.aar')) as aar:
    kuikly.write_bytes(aar.read('classes.jar'))
classpath = [stdlib, annotations, runtime, kuikly,
             artifact('androidx.collection', 'collection-jvm', '1.5.0'),
             artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2')]
sources = sorted(Path(__file__).parent.glob('*.kt')) + [
    ROOT / 'live-kuikly/src/commonMain/kotlin/io/github/gycrosskit/livesdk/kuikly/LiveVideo.kt',
    ROOT / 'live-kuikly/src/commonMain/kotlin/io/github/gycrosskit/livesdk/KuiklyLiveEventDecoding.kt']
sources += [ROOT / 'live-core/src/commonMain/kotlin/io/github/gycrosskit/livesdk' / name for name in (
    'LiveLikeReporter.kt', 'AtomicAudienceContracts.kt', 'LiveNativeContracts.kt')]
command = ['java', '-cp', os.pathsep.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
           '-no-stdlib', '-no-reflect', '-nowarn', '-jvm-target', '11', '-classpath',
           os.pathsep.join(map(str, classpath)), '-Xplugin=' + str(plugin), '-d', str(OUT / 'tests.jar'),
           *map(str, sources)]
consumer = command[:command.index('-classpath')] + ['-classpath',
    os.pathsep.join(map(str, [OUT / 'tests.jar', *classpath])), '-Xplugin=' + str(plugin),
    '-module-name', 'public-consumer', '-d', str(OUT / 'consumer.jar'),
    str(ROOT / 'verification/consumer/src/kuiklyComposeMain/kotlin/ComposeApiConsumer.kt')]
for name, args in [('compile', command), ('public-consumer', consumer), ('tests', ['java', '-cp',
        os.pathsep.join(map(str, [OUT / 'tests.jar', *classpath])),
        'io.github.gycrosskit.livesdk.verification.MainKt'])]:
    result = subprocess.run(args, capture_output=True, text=True)
    (OUT / f'{name}.log').write_text(result.stdout + result.stderr)
    print(result.stdout + result.stderr, end='', flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
