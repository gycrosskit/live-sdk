#!/usr/bin/env python3
"""轻量检查产品边界与锁校验器；Swift 行为另由真实 Pod Simulator 测试执行。"""
from pathlib import Path
import re
import os
import sys
import hashlib
import json
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def verify_remote_source(text, tag, commit):
    assert re.fullmatch(r"native-\d+\.\d+\.\d+(?:-rc\.\d+)?", tag), tag
    assert re.fullmatch(r"[a-f0-9]{40}", commit), "Expected exact release commit"
    options = text.split("CHECKOUT OPTIONS:\n", 1)[1].split("\n\n", 1)[0]
    component = options.split("  GycLiveNative:\n", 1)[1].split("\n  OpenKuikly", 1)[0]
    assert re.search(r"^    :tag: " + re.escape(tag) + r"$", component, re.M), "Wrong Native tag"
    assert re.search(r"^    :git: https://github\.com/gycrosskit/live-sdk\.git$", component, re.M), "Wrong Native repository"
    recorded = re.search(r"^    :commit: (.+)$", component, re.M)
    if recorded:
        assert recorded.group(1) == commit, "Wrong optional commit"
    assert ":path:" not in text, "Remote proof must not use local Pod paths"


def verify_tag_refs(text, tag, commit):
    refs = dict(line.split()[::-1] for line in text.splitlines())
    ref = "refs/tags/" + tag
    assert ref in refs, "Native tag does not exist"
    assert refs.get(ref + "^{}", refs[ref]) == commit, "Remote tag moved from frozen checkout"


def verify_installed_swift(installed, expected, required):
    files = {path.relative_to(installed).as_posix(): path.read_bytes()
             for path in installed.rglob("*.swift")}
    assert files and required and required <= files.keys(), "Product Swift sources missing"
    for name, content in files.items():
        assert name in expected and content == expected[name], "Installed Swift differs: " + name
    return {name: hashlib.sha256(content).hexdigest() for name, content in sorted(files.items())}


def verify_remote_checkout(lock, product):
    tag, commit = os.environ["LIVE_NATIVE_TAG"], os.environ["LIVE_NATIVE_COMMIT"]
    verify_remote_source(lock.read_text(), tag, commit)
    def git(*args):
        return subprocess.check_output(["git", *args], cwd=ROOT)
    assert git("rev-parse", "HEAD").decode().strip() == commit, "Checkout is not frozen release commit"
    refs = git("ls-remote", "--tags", "https://github.com/gycrosskit/live-sdk.git",
               "refs/tags/" + tag, "refs/tags/" + tag + "^{}").decode()
    verify_tag_refs(refs, tag, commit)
    tracked = git("ls-tree", "-r", "--name-only", commit).decode().splitlines()
    expected = {name: git("show", commit + ":" + name) for name in tracked if name.endswith(".swift")}
    required = {name for name in expected if name.startswith("ios/Sources/" + product + "/")}
    hashes = verify_installed_swift(lock.parent / "Pods/GycLiveNative", expected, required)
    proof = {"tag": tag, "commit": commit, "product": product,
             "lockSha256": hashlib.sha256(lock.read_bytes()).hexdigest(), "installedSwiftSha256": hashes}
    (lock.parent / "native-source-proof.json").write_text(json.dumps(proof, indent=2) + "\n")
    print("Exact remote tag/SHA and installed Swift bytes verified:", tag, commit, len(hashes))


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

    def test_tag_only_lock_accepts_no_commit_and_rejects_wrong_source(self):
        commit = 'a' * 40
        # CocoaPods tag sources record git/tag, without a synthetic commit field.
        valid = "CHECKOUT OPTIONS:\n  GycLiveNative:\n    :git: https://github.com/gycrosskit/live-sdk.git\n    :tag: native-0.2.1-rc.15\n"
        verify_remote_source(valid, 'native-0.2.1-rc.15', commit)
        for invalid in (valid.replace('rc.15', 'rc.150'), valid.replace('live-sdk.git', 'wrong.git'),
                        valid + ':path: /tmp/source', valid + '    :commit: ' + 'b' * 40 + '\n'):
            with self.assertRaises(AssertionError):
                verify_remote_source(invalid, 'native-0.2.1-rc.15', commit)

    def test_remote_tag_must_match_frozen_commit_for_lightweight_and_annotated(self):
        tag, commit = 'native-0.2.1-rc.15', 'a' * 40
        plain = commit + '\trefs/tags/' + tag + '\n'
        annotated = 'b' * 40 + '\trefs/tags/' + tag + '\n' + plain.rstrip() + '^{}\n'
        verify_tag_refs(plain, tag, commit)
        verify_tag_refs(annotated, tag, commit)
        for invalid in (plain.replace(commit, 'b' * 40), plain.replace('rc.15', 'rc.150'), ''):
            with self.assertRaises(AssertionError):
                verify_tag_refs(invalid, tag, commit)

    def test_installed_swift_rejects_missing_extra_and_modified_files(self):
        name = 'ios/Sources/GycLiveRenderer/View.swift'
        expected = {name: b'public class Renderer {}\n'}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            file = root / name
            file.parent.mkdir(parents=True)
            with self.assertRaises(AssertionError):
                verify_installed_swift(root, expected, {name})
            file.write_bytes(expected[name])
            self.assertEqual(set(verify_installed_swift(root, expected, {name})), {name})
            file.write_bytes(b'modified')
            with self.assertRaises(AssertionError):
                verify_installed_swift(root, expected, {name})
            file.write_bytes(expected[name])
            (root / 'Extra.swift').write_text('extra')
            with self.assertRaises(AssertionError):
                verify_installed_swift(root, expected, {name})

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
        product = "GycLiveNative" if sys.argv[1] == "--core-lock" else "GycLiveRenderer"
        verify_remote_checkout(Path(sys.argv[2]), product)
    if len(sys.argv) == 3 and sys.argv[1] == "--core-lock":
        verify_core_lock(Path(sys.argv[2]).read_text())
        print("Default Pod lock: Core SDK dependencies retained, no Renderer")
    elif len(sys.argv) == 3 and sys.argv[1] == "--lock":
        verify_lock(Path(sys.argv[2]).read_text())
        print("Renderer-only Pod lock: 2.29.1, no Core/vendor/KMP dependency")
    else:
        unittest.main()
