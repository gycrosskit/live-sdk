#!/usr/bin/env bash
# Registry 与 Release HAR 独立消费；Release 模式不能当作 Registry 验收。
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# release 事件从已冻结的 consumer 读取独立 HAR 版本，dispatch 可显式指定。
if [[ -z "${VERSION:-}" ]]; then
  VERSION="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["dependencies"]["@gycrosskit/live-native"])' "$TASK_ROOT/verification-ohos/consumer/oh-package.json5")"
fi
source "$TASK_ROOT/scripts/prepare-ohos-clt.sh"
TASK_CONSUMER="$TASK_ROOT/build/ohos-remote-consumer"
[[ ! -e "$TASK_CONSUMER" ]] || { echo 'Consumer directory must be empty' >&2; exit 2; }
cp -R "$TASK_ROOT/verification-ohos" "$TASK_CONSUMER"
cd "$TASK_CONSUMER"
python3 - "$VERSION" <<'PY'
import json, sys
from pathlib import Path
manifest = json.loads(Path('consumer/oh-package.json5').read_text())
assert manifest['dependencies']['@gycrosskit/live-native'] == sys.argv[1]
PY
if [[ -n "${RELEASE_HAR_TAG:-}" ]]; then
  [[ "$RELEASE_HAR_TAG" =~ ^[0-9]+\.[0-9]+\.[0-9]+-rc\.[0-9]+$ ]] || exit 2
  curl --fail --location --proto '=https' --proto-redir '=https' --connect-timeout 30 --max-time 120 \
    "https://github.com/gycrosskit/live-sdk/releases/download/$RELEASE_HAR_TAG/GycLiveNative.har" -o consumer/LiveNative.har
  python3 - "$TASK_ROOT" "$VERSION" <<'PYTHON'
import hashlib, json, sys, tarfile
from pathlib import Path
root, version = Path(sys.argv[1]), sys.argv[2]
archive = Path('consumer/LiveNative.har')
expected = dict(line.split() for line in (root / 'release-har-checksums.txt').read_text().splitlines()
                if line.strip() and not line.startswith('#'))[version]
assert hashlib.sha256(archive.read_bytes()).hexdigest() == expected
with tarfile.open(archive) as har:
    package = json.load(har.extractfile('package/oh-package.json5'))
    assert package['name'] == '@gycrosskit/live-native' and package['version'] == version
manifest = Path('consumer/oh-package.json5')
data = json.loads(manifest.read_text())
data['dependencies']['@gycrosskit/live-native'] = 'file:./LiveNative.har'
manifest.write_text(json.dumps(data, indent=2) + '\n')
PYTHON
fi
for TASK_ATTEMPT in 1 2 3; do
  if ohpm install --all; then break; fi
  [[ "$TASK_ATTEMPT" != 3 ]] || exit 1
  sleep 10
done
if [[ -z "${RELEASE_HAR_TAG:-}" ]]; then
  python3 "$TASK_ROOT/scripts/check-ohpm-consumer.py" "$TASK_ROOT" "$TASK_CONSUMER" "$VERSION"
else
  python3 - "$TASK_ROOT" "$VERSION" "$RELEASE_HAR_TAG" <<'PYTHON'
import hashlib, json, subprocess, sys
from pathlib import Path
root, version, tag = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
module = Path('consumer/oh_modules/@gycrosskit/live-native')
assert module.resolve().is_relative_to(Path.cwd())
package = json.loads((module / 'oh-package.json5').read_text())
assert package['name'] == '@gycrosskit/live-native' and package['version'] == version
receipt = {'release_tag': tag, 'har_version': version,
           'sha256': hashlib.sha256(Path('consumer/LiveNative.har').read_bytes()).hexdigest(),
           'verifier_sha': subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip(),
           'scope': 'Downloaded frozen GitHub Release HAR; not OHPM Registry acceptance; compile follows separately'}
(root / 'ci-diagnostics').mkdir(exist_ok=True)
(root / 'ci-diagnostics/release-har-consumer-proof.json').write_text(json.dumps(receipt, indent=2) + '\n')
PYTHON
fi
bash "$TASK_CLT/bin/hvigorw" --mode module -p product=default -p module=LiveRemoteConsumer@default -p buildMode=release assembleHar --no-daemon
cp consumer/oh-package-lock.json5 "$TASK_ROOT/ci-diagnostics/${RELEASE_HAR_TAG:+release-har-}consumer-lock.json5"
