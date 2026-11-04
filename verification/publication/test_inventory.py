"""新增房间协议发布物不改变历史标签的预期清单。"""
from pathlib import Path
import runpy
import unittest

inventory = runpy.run_path(str(Path(__file__).resolve().parents[2] / "scripts/maven-publications.py"))


class PublicationInventoryTest(unittest.TestCase):
    def test_old_versions_keep_the_two_product_inventory(self):
        for version in ("0.3.0-rc.2", "1.0.0", "1.1.2"):
            with self.subTest(version=version):
                actual = inventory["publications"](version)
                self.assertEqual(12, len(actual))
                self.assertFalse(any(name.startswith("live-room-core") for name in actual))

    def test_room_candidate_and_future_versions_include_jvm_and_all_native_targets(self):
        for version in ("1.1.3-rc.1", "1.1.3", "1.1.10", "1.2.0", "2.0.0"):
            with self.subTest(version=version):
                actual = inventory["publications"](version)
                self.assertEqual(19, len(set(actual)))
                self.assertEqual({"live-room-core", "live-room-core-android", "live-room-core-jvm", "live-room-core-iosarm64", "live-room-core-iosx64", "live-room-core-iossimulatorarm64", "live-room-core-ohosarm64"}, {name for name in actual if name.startswith("live-room-core")})

    def test_invalid_version_fails_closed(self):
        for version in ("latest", "1.1", "1.1.3/other"):
            with self.assertRaises(ValueError):
                inventory["publications"](version)


if __name__ == "__main__":
    unittest.main()
