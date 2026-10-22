#!/usr/bin/env python3
"""归档门禁测试；不代表 Hvigor 编译或 SDK 运行。"""
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('verify_har', ROOT / 'scripts/verify-ohos-har.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class HarIdentityTest(unittest.TestCase):
    def test_accepts_identity_and_rejects_stale_version_exports_and_duplicate_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            metadata = {'name': '@gycrosskit/live-native', 'version': '0.3.0-rc.1', 'dependencies': {'sdk': '1'}}
            (source / 'oh-package.json5').write_text(json.dumps(metadata))
            (source / 'Index.ets').write_text('export class Live {}')
            outputs = source / 'build/default/outputs/default'
            outputs.mkdir(parents=True)
            for invalid in (None, 'version', 'exports', 'duplicate'):
                with self.subTest(invalid=invalid):
                    package = dict(metadata)
                    if invalid == 'version':
                        package['version'] = 'old'
                    with tarfile.open(outputs / 'live.har', 'w:gz') as archive:
                        files = [('package/oh-package.json5', json.dumps(package)),
                                 ('package/Index.ets', 'stale' if invalid == 'exports' else 'export class Live {}')]
                        if invalid == 'duplicate':
                            files.append(files[0])
                        for name, text in files:
                            data = text.encode()
                            entry = tarfile.TarInfo(name)
                            entry.size = len(data)
                            archive.addfile(entry, io.BytesIO(data))
                    if invalid:
                        with self.assertRaises(ValueError):
                            module.verify(source, source / 'result.har')
                    else:
                        module.verify(source, source / 'result.har')
                        self.assertEqual((source / 'result.har').read_bytes(), (outputs / 'live.har').read_bytes())

    def test_requires_compiler_output(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                module.verify(directory, Path(directory) / 'result.har')


if __name__ == '__main__':
    unittest.main()
