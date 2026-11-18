#!/usr/bin/env bash
# 历史公共 OHPM 消费验收；私有 OHPM 发布和下载由 Jenkins 回执确认。
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${VERIFY_OHPM_SHA256:?Provide the frozen archive SHA-256 from the release receipt}"
[[ "$VERIFY_OHPM_SHA256" =~ ^[a-f0-9]{64}$ ]] || exit 2
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
for TASK_ATTEMPT in 1 2 3; do
  if ohpm install --all; then break; fi
  [[ "$TASK_ATTEMPT" != 3 ]] || exit 1
  sleep 10
done
python3 "$TASK_ROOT/scripts/check-ohpm-consumer.py" "$TASK_ROOT" "$TASK_CONSUMER" "$VERSION" "$VERIFY_OHPM_SHA256"
bash "$TASK_CLT/bin/hvigorw" --mode module -p product=default -p module=LiveRemoteConsumer@default -p buildMode=release assembleHar --no-daemon
cp consumer/oh-package-lock.json5 "$TASK_ROOT/ci-diagnostics/consumer-lock.json5"
