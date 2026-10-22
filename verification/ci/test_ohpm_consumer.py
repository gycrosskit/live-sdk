"""Registry 字节与消费锁的防回退检查，网络替身不算公开消费。"""
import base64
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('check_ohpm', ROOT / 'scripts/check-ohpm-consumer.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RegistryProofTest(unittest.TestCase):
    def test_exact_registry_bytes_and_lock_required(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            consumer = root / 'consumer-project'
            package = consumer / 'consumer/oh_modules/@gycrosskit/live-native'
            package.mkdir(parents=True)
            data = b'frozen-har-fixture'
            version = '0.3.0-rc.2'
            digest = hashlib.sha256(data).hexdigest()
            integrity = 'sha512-' + base64.b64encode(hashlib.sha512(data).digest()).decode()
            url = 'https://ohpm.openharmony.cn/ohpm/@gycrosskit/live-native/-/live-native-' + version + '.har'
            metadata = {'name': '@gycrosskit/live-native', 'version': version,
                        'dist': {'tarball': url, 'integrity': integrity}}
            (root / 'release-har-checksums.txt').write_text(version + ' ' + digest + '\n')
            (package / 'oh-package.json5').write_text(json.dumps(metadata))
            lock_path = consumer / 'consumer/oh-package-lock.json5'
            for invalid in (None, 'bytes', 'lock', 'origin'):
                with self.subTest(invalid=invalid):
                    lock = {'packages': {'@gycrosskit/live-native@' + version: {
                        'resolved': 'file:local.har' if invalid == 'lock' else url, 'integrity': integrity}}}
                    lock_path.write_text(json.dumps(lock))
                    info = json.loads(json.dumps(metadata))
                    if invalid == 'origin':
                        info['dist']['tarball'] = 'https://example.com/live.har'
                    replies = [io.BytesIO(json.dumps({'versions': {version: info}}).encode()),
                               io.BytesIO(b'changed' if invalid == 'bytes' else data)]
                    with patch.object(module, 'urlopen', side_effect=replies):
                        if invalid:
                            with self.assertRaises(AssertionError):
                                module.verify(root, consumer, version)
                        else:
                            module.verify(root, consumer, version)
                            proof = json.loads((root / 'ci-diagnostics/ohpm-consumer-proof.json').read_text())
                            self.assertEqual(proof['sha256'], digest)


if __name__ == '__main__':
    unittest.main()
