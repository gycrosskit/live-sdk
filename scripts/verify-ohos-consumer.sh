#!/usr/bin/env bash
# 远程 Registry HAR 消费；不接受 source/file 依赖或当前工程替代。
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${VERSION:?Pass the immutable HAR release version}"
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
python3 "$TASK_ROOT/scripts/check-ohpm-consumer.py" "$TASK_ROOT" "$TASK_CONSUMER" "$VERSION"
bash "$TASK_CLT/bin/hvigorw" --mode module -p product=default -p module=LiveRemoteConsumer@default -p buildMode=release assembleHar --no-daemon
cp consumer/oh-package-lock.json5 "$TASK_ROOT/ci-diagnostics/ohpm-consumer-lock.json5"
