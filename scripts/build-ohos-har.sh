#!/usr/bin/env bash
# 仅由远程 runner 生产 HAR；不使用本机 DevEco 代替 CI。
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$TASK_ROOT/scripts/prepare-ohos-clt.sh"
cd "$TASK_ROOT/ohos"
for TASK_ATTEMPT in 1 2 3; do
  if ohpm install --all; then break; fi
  [[ "$TASK_ATTEMPT" != 3 ]] || exit 1
  echo "OHPM install failed; retry $TASK_ATTEMPT/2 in 10 seconds" >&2
  sleep 10
done
bash "$TASK_CLT/bin/hvigorw" --mode module -p product=default -p module=GycLiveNative@default -p buildMode=release assembleHar --no-daemon
python3 "$TASK_ROOT/scripts/verify-ohos-har.py" "$TASK_ROOT/ohos/live-native" "$TASK_OUTPUT/GycLiveNative.har"
git -C "$TASK_ROOT" rev-parse HEAD > "$TASK_OUTPUT/SOURCE_SHA"
(cd "$TASK_OUTPUT" && sha256sum GycLiveNative.har > SHA256SUMS)
