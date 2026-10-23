#!/usr/bin/env python3
"""只归档当前源包身份匹配的单个 Hvigor HAR。"""
import json
from pathlib import Path
import shutil
import sys
import tarfile


def verify(source, destination):
    source = Path(source)
    archives = list((source / 'build').glob('**/outputs/**/*.har'))
    if len(archives) != 1:
        raise ValueError('Expected one Hvigor HAR, found ' + str(len(archives)))
    expected = json.loads((source / 'oh-package.json5').read_text())
    with tarfile.open(archives[0], 'r:gz') as har:
        members = har.getmembers()
        if len({member.name for member in members}) != len(members) or any(not (member.isfile() or member.isdir()) for member in members):
            raise ValueError('HAR contains ambiguous or linked members')
        metadata_file = har.extractfile('package/oh-package.json5')
        if metadata_file is None:
            raise ValueError('HAR package metadata missing')
        actual = json.load(metadata_file)
        for key in ('name', 'version', 'dependencies'):
            if actual.get(key) != expected.get(key):
                raise ValueError('HAR metadata differs from source: ' + key)
        if har.extractfile('package/Index.ets').read() != (source / 'Index.ets').read_bytes():
            raise ValueError('HAR exports differ from source')
    shutil.copy2(archives[0], destination)


if __name__ == '__main__':
    verify(*sys.argv[1:])
