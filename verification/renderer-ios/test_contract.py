#!/usr/bin/env python3
"""轻量检查产品边界与锁校验器；Swift 行为另由真实 Pod Simulator 测试执行。"""
from pathlib import Path
import re
import os
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]


def verify_remote_source(text, tag, commit):
    assert re.fullmatch(r"native-\d+\.\d+\.\d+(?:-rc\.\d+)?", tag), tag
    assert re.fullmatch(r"[a-f0-9]{40}", commit), "Expected exact release commit"
    options = text.split("CHECKOUT OPTIONS:\n", 1)[1].split("\n\n", 1)[0]
    component = options.split("  GycLiveNative:\n", 1)[1].split("\n  OpenKuikly", 1)[0]
    assert f":tag: {tag}" in component
    assert f":commit: {commit}" in component
    assert ":git: https://github.com/gycrosskit/live-sdk.git" in component
    assert ":path:" not in text, "Remote proof must not use local Pod paths"


def verify_lock(text):
    pods = text.split("PODS:\n", 1)[1].split("\n\n", 1)[0]
    names = set(re.findall(r"^  - ([^ (]+)", pods, re.M))
    assert names == {"GycLiveNative/Renderer", "OpenKuiklyIOSRender"}, names
    assert "OpenKuiklyIOSRender (2.29.1)" in pods, "Host renderer version differs"
    assert ":tag: 2.29.1" in text, "Missing immutable renderer tag"


def verify_core_lock(text):
    pods = text.split("PODS:\n", 1)[1].split("\n\n", 1)[0]
    assert "GycLiveNative/Core (" in pods, "Default consumer lost Core"
    assert "GycLiveNative/Renderer" not in pods and "OpenKuiklyIOSRender" not in pods
    for sdk in ("AtomicXCore (4.3.9)", "RTCRoomEngine/Professional (4.3.9)",
                "TXIMSDK_Plus_iOS_XCFramework (9.1.7818)"):
        assert sdk in pods, sdk


class RendererContractTest(unittest.TestCase):
    def test_renderer_only_lock_rejects_core_vendor_or_wrong_renderer(self):
        valid = "PODS:\n  - GycLiveNative/Renderer (0.2.1-rc.13):\n    - OpenKuiklyIOSRender (< 2.30.0, >= 2.28.0)\n  - OpenKuiklyIOSRender (2.29.1)\n\nEXTERNAL SOURCES:\n  OpenKuiklyIOSRender:\n    :tag: 2.29.1\n"
        verify_lock(valid)
        for invalid in (valid.replace("/Renderer", "/Core"),
                        valid.replace("\n\nEXTERNAL", "\n  - AtomicXCore (4.3.9)\n\nEXTERNAL"),
                        valid.replace("2.29.1", "2.28.0"),
                        valid.replace(":tag:", ":branch:")):
            with self.assertRaises(AssertionError):
                verify_lock(invalid)

    def test_default_core_lock_keeps_sdk_without_renderer(self):
        valid = "PODS:\n  - GycLiveNative/Core (0.2.1-rc.13)\n  - AtomicXCore (4.3.9)\n  - RTCRoomEngine/Professional (4.3.9)\n  - TXIMSDK_Plus_iOS_XCFramework (9.1.7818)\n"
        verify_core_lock(valid)
        for invalid in (valid.replace("/Core", "/Renderer"), valid + "  - OpenKuiklyIOSRender (2.29.1)\n",
                        valid.replace("9.1.7818", "9.1.0")):
            with self.assertRaises(AssertionError):
                verify_core_lock(invalid)

    def test_remote_source_rejects_wrong_tag_commit_and_path(self):
        commit = 'a' * 40
        valid = "CHECKOUT OPTIONS:\n  GycLiveNative:\n    :commit: " + commit + "\n    :git: https://github.com/gycrosskit/live-sdk.git\n    :tag: native-0.2.1-rc.14\n"
        verify_remote_source(valid, 'native-0.2.1-rc.14', commit)
        for invalid in (valid.replace('rc.14', 'rc.13'), valid.replace(commit, 'b' * 40), valid + ':path: /tmp/source'):
            with self.assertRaises(AssertionError):
                verify_remote_source(invalid, 'native-0.2.1-rc.14', commit)

    def test_native_release_skips_maven_jobs(self):
        workflow = (ROOT / '.github/workflows/release-validation.yml').read_text()
        for job in ('verify-public', 'release-android', 'release-native'):
            section = workflow.split('  ' + job + ':\n', 1)[1].split('    runs-on:', 1)[0]
            self.assertIn("!startsWith(github.event.release.tag_name || inputs.version, 'native-')", section)
        pod = workflow.split('  release-pod:\n', 1)[1]
        self.assertIn('LIVE_NATIVE_TAG:', pod)
        self.assertIn('verify-live-native-ios.sh', pod)
        self.assertIn('verify-live-renderer-ios.sh', pod)
        self.assertNotIn('gradlew', pod)

    def test_pod_default_keeps_core_and_renderer_does_not_depend_on_core(self):
        spec = (ROOT / "GycLiveNative.podspec").read_text()
        self.assertIn("s.default_subspecs = 'Core'", spec)
        core = spec.split("s.subspec 'Core' do |core|", 1)[1].split("\n  end", 1)[0]
        renderer = spec.split("s.subspec 'Renderer' do |renderer|", 1)[1].split("\n  end", 1)[0]
        self.assertIn("ios/Sources/GycLiveNative/*.swift", core)
        self.assertEqual(re.findall(r"core.dependency '([^']+)', '([^']+)'", core), [
            ("AtomicXCore", "4.3.9"), ("RTCRoomEngine/Professional", "4.3.9"),
            ("TXIMSDK_Plus_iOS_XCFramework", "9.1.7818")])
        self.assertEqual(re.findall(r"(?:s|renderer).dependency '([^']+)'", spec), ["OpenKuiklyIOSRender"])
        self.assertIn("'>= 2.28.0', '< 2.30.0'", renderer)
        self.assertIn("ios/Sources/GycLiveRenderer/*.swift", renderer)

    def test_native_renderer_does_not_import_kotlin_or_host(self):
        source = (ROOT / "ios/Sources/GycLiveRenderer/GycLiveRendererView.swift").read_text()
        self.assertEqual(re.findall(r"^import (.+)$", source, re.M), ["UIKit", "OpenKuiklyIOSRender"])
        self.assertIn("@objc(setCss_liveEvent:)", source)
        self.assertNotIn("as? KuiklyRenderCallback", source)
        adapter = (ROOT / "live-kuikly/ios/GycLiveView.swift").read_text()
        self.assertIn("class GycLiveView: GycLiveRendererView", adapter)
        self.assertIn("import GycLiveNative", adapter)
        self.assertIn("public var likeReporter: LiveLikeReporter?", adapter)
        self.assertNotIn("pendingEvents", adapter)


if __name__ == "__main__":
    if len(sys.argv) == 3 and os.environ.get("LIVE_NATIVE_TAG"):
        verify_remote_source(Path(sys.argv[2]).read_text(), os.environ["LIVE_NATIVE_TAG"],
                             os.environ["LIVE_NATIVE_COMMIT"])
        print("Exact remote Native tag/commit lock verified")
    if len(sys.argv) == 3 and sys.argv[1] == "--core-lock":
        verify_core_lock(Path(sys.argv[2]).read_text())
        print("Default Pod lock: Core SDK dependencies retained, no Renderer")
    elif len(sys.argv) == 3 and sys.argv[1] == "--lock":
        verify_lock(Path(sys.argv[2]).read_text())
        print("Renderer-only Pod lock: 2.29.1, no Core/vendor/KMP dependency")
    else:
        unittest.main()
