#!/usr/bin/env python3
"""核对 Registry 字节、安装锁和精确发布摘要，不替代 ArkTS 编译。"""
import base64
import hashlib
import json
from pathlib import Path
import sys
from urllib.request import urlopen
from urllib.parse import urlsplit


def verify(root, consumer, version):
    expected = dict(line.split() for line in (root / 'release-har-checksums.txt').read_text().splitlines()
                    if line.strip() and not line.startswith('#'))[version]
    with urlopen('https://ohpm.openharmony.cn/ohpm/@gycrosskit%2Flive-native', timeout=30) as response:
        metadata = json.load(response)['versions'][version]
    assert metadata['name'] == '@gycrosskit/live-native' and metadata['version'] == version
    url = metadata['dist']['tarball']
    origin = urlsplit(url)
    assert origin.scheme == 'https' and origin.hostname == 'ohpm.openharmony.cn' and not origin.username
    with urlopen(url, timeout=30) as response:
        data = response.read()
    assert hashlib.sha256(data).hexdigest() == expected, 'Registry bytes differ from frozen HAR'
    integrity = 'sha512-' + base64.b64encode(hashlib.sha512(data).digest()).decode()
    assert metadata['dist']['integrity'] == integrity
    lock = json.loads('\n'.join(line for line in (consumer / 'consumer/oh-package-lock.json5').read_text().splitlines()
                                if not line.lstrip().startswith('//')))
    installed = lock['packages']['@gycrosskit/live-native@' + version]
    assert installed['resolved'] == url and installed['integrity'] == integrity
    module = consumer / 'consumer/oh_modules/@gycrosskit/live-native'
    assert module.resolve().is_relative_to(consumer.resolve()), 'Consumer must use its own installed dependency'
    package = json.loads((module / 'oh-package.json5').read_text())
    assert package['version'] == version and package['name'] == metadata['name']
    receipt = {'version': version, 'sha256': expected, 'integrity': integrity, 'registry_url': url,
               'installed_path': str(module.resolve()), 'scope': 'Registry download and install; compile follows separately'}
    output = root / 'ci-diagnostics'
    output.mkdir(exist_ok=True)
    (output / 'ohpm-consumer-proof.json').write_text(json.dumps(receipt, indent=2) + '\n')


if __name__ == '__main__':
    verify(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3])
