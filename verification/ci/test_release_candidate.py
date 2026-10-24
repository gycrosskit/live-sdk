"""Exercise retained Native identity handoff with fixtures, without SDK/network calls."""
import json
import os
import shutil
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[2]
workflow = json.loads(subprocess.check_output([
    'ruby', '-ryaml', '-rjson', '-e',
    'puts JSON.generate(YAML.safe_load(File.read(ARGV.fetch(0))))',
    str(ROOT / '.github/workflows/release-validation.yml'),
], text=True))
job = workflow['jobs']['release-native']
assert job['env'] == {'LIVE_NATIVE_TAG': 'native-1.0.0'}
paired_commit = 'c' * 40
assert 'refs/tags/$VERSION' in ''.join(step.get('run', '') for step in job['steps'])
for name in ('release-android', 'release-native'):
    consumers = [step['run'] for step in workflow['jobs'][name]['steps']
                 if '-p verification/consumer' in step.get('run', '')]
    assert consumers and all('-PremoteOnly' in command and '-PverifyLocalSource' not in command
                             for command in consumers)
steps = {step.get('name'): step.get('run') for step in job['steps']}
build = steps['Build exact retained remote Native Renderer']
link = steps['Link new Maven with retained Native Renderer']
assert 'linkDebugFrameworkIosArm64' in link and 'bash scripts/verify-ios-native.sh' in link
assert '-PkuiklyRenderFrameworkDir=$LIVE_KUIKLY_IOS_FRAMEWORK_DIR' in link
assert '"-PliveVersion=$VERSION"' in link and '-PverifyKuiklyCompose' in link

for head, expected in [(paired_commit, True), ('a' * 40, False)]:
    with TemporaryDirectory() as folder:
        root = Path(folder)
        (root / 'ci-diagnostics').mkdir()
        (root / 'tools').mkdir()
        (root / 'scripts').mkdir()
        renderer = root / 'verification/renderer-ios'
        renderer.mkdir(parents=True)
        git = root / 'tools/git'
        git.write_text('''#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  clone)
    test "$6" = "$LIVE_NATIVE_TAG"
    dest="${!#}"
    mkdir -p "$dest/scripts" "$dest/verification/renderer-ios" "$dest/ios/Sources/Renderer"
    printf 'exit 97' > "$dest/scripts/verify-live-renderer-ios.sh"
    printf 'raise AssertionError("stale checker executed")' > "$dest/verification/renderer-ios/test_contract.py"
    printf old-podfile > "$dest/verification/renderer-ios/Podfile"
    printf old-tests > "$dest/verification/renderer-ios/RendererTests.swift"
    printf old-production > "$dest/ios/Sources/Renderer/Fixture.swift"
    ;;
  -C)
    if [[ "$3" = diff ]]; then
      test "$(cat "$2/ios/Sources/Renderer/Fixture.swift")" = old-production
    else
      printf '%s\\n' "$MOCK_HEAD"
    fi
    ;;
  fetch) exit 0 ;;
  rev-parse) if [[ "${2:-}" = 'FETCH_HEAD^{commit}' ]]; then printf '%s\\n' "$PAIRED_COMMIT"; else printf '%s\\n' "$GITHUB_SHA"; fi ;;
  *) exit 1 ;;
esac
''')
        git.chmod(0o755)
        (root / 'scripts/ci-run.py').write_text('''import subprocess, sys
sys.exit(subprocess.run(sys.argv[sys.argv.index('--') + 1:]).returncode)
''')
        (renderer / 'Podfile').write_text('current-remote-podfile')
        (renderer / 'RendererTests.swift').write_text('current-renderer-tests')
        (renderer / 'test_contract.py').write_text('''import json, os, subprocess
from pathlib import Path
root = Path(__file__).resolve().parents[2]
assert subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip() == os.environ['LIVE_NATIVE_COMMIT']
assert root.name == 'live-release-native'
(root / 'build/native-renderer-consumer/native-source-proof.json').write_text(json.dumps({'checker': 'current-candidate', 'native_commit': os.environ['LIVE_NATIVE_COMMIT']}))
''')
        (root / 'scripts/verify-live-renderer-ios.sh').write_text('''#!/usr/bin/env bash
set -euo pipefail
test "$LIVE_NATIVE_TAG" = native-1.0.0
test "$LIVE_NATIVE_COMMIT" = "$PAIRED_COMMIT"
test "$(cat verification/renderer-ios/Podfile)" = current-remote-podfile
test "$(cat verification/renderer-ios/RendererTests.swift)" = current-renderer-tests
test "$(cat ios/Sources/Renderer/Fixture.swift)" = old-production
mkdir -p build/native-renderer-consumer/iphoneos
printf lock > build/native-renderer-consumer/Podfile.lock
python3 verification/renderer-ios/test_contract.py
''')
        env = dict(os.environ, **job['env'], PATH=str(root / 'tools') + os.pathsep + os.environ['PATH'],
                   RUNNER_TEMP=str(root), GITHUB_WORKSPACE=str(root), GITHUB_SHA='b' * 40,
                   GITHUB_ENV=str(root / 'github-env'), MOCK_HEAD=head, PAIRED_COMMIT=paired_commit, VERSION='1.0.0')
        result = subprocess.run(['bash', '-euo', 'pipefail', '-c', build], cwd=root,
                                env=env, capture_output=True, text=True)
        assert (result.returncode == 0) == expected, result.stdout + result.stderr
        proof_path = root / 'ci-diagnostics/release-renderer-source-proof.json'
        assert proof_path.exists() == expected
        if expected:
            proof = json.loads(proof_path.read_text())
            assert proof == {'checker': 'current-candidate', 'native_commit': paired_commit}
            receipt = json.loads((root / 'ci-diagnostics/release-renderer-runner-proof.json').read_text())
            assert receipt['candidate_commit'] == env['GITHUB_SHA'] != receipt['native_commit']
            assert receipt['native_tag'] == job['env']['LIVE_NATIVE_TAG']
            import hashlib
            assert set(receipt['runner_sha256']) == {'scripts/verify-live-renderer-ios.sh', 'verification/renderer-ios/Podfile', 'verification/renderer-ios/RendererTests.swift', 'verification/renderer-ios/test_contract.py'}
            for name, digest in receipt['runner_sha256'].items():
                assert digest == hashlib.sha256((root / name).read_bytes()).hexdigest()
                assert (root / name).read_bytes() == (root / 'live-release-native' / name).read_bytes()
            assert (root / 'github-env').read_text().splitlines() == [
                f'{key}={root}/live-release-native/build/native-renderer-consumer/iphoneos'
                for key in ('LIVE_RENDERER_IOS_FRAMEWORK_DIR', 'LIVE_KUIKLY_IOS_FRAMEWORK_DIR')]

for tag, allowed in [('', True), ('0.3.0-rc.2', True), ('1.0.0', True),
                     ('har-1.0.0', True), ('har-1.0.0-rc.1', True),
                     ('main', False), ('../1.0.0', False), ('har-main', False), ('1.0.0-rc.no', False)]:
    with TemporaryDirectory() as folder:
        root = Path(folder)
        (root / 'scripts').mkdir()
        shutil.copyfile(ROOT / 'scripts/verify-ohos-consumer.sh', root / 'scripts/verify-ohos-consumer.sh')
        (root / 'scripts/prepare-ohos-clt.sh').write_text('touch "$TASK_ROOT/clt-reached"\nexit 73\n')
        result = subprocess.run(['bash', str(root / 'scripts/verify-ohos-consumer.sh')],
                                env=dict(os.environ, VERSION='1.0.0', RELEASE_HAR_TAG=tag),
                                capture_output=True, text=True, timeout=10)
        assert result.returncode == (73 if allowed else 2), (tag, result.returncode)
        assert (root / 'clt-reached').exists() == allowed

print('PASS: retained Native identity handoff and formal/RC HAR Tag boundary; invalid Tag fails before CLT or network. Fixture only.')
