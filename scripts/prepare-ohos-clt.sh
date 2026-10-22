#!/usr/bin/env bash
# 由远程构建和公开依赖消费入口 source，复用相同工具链验证。
[[ "$(uname -s)" == Linux && "${GITHUB_ACTIONS:-}" == true ]] || {
  echo 'HAR compilation requires a remote GitHub Actions Linux runner' >&2; exit 2;
}
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TASK_OUTPUT="$TASK_ROOT/build/ohos-har"
mkdir -p "$TASK_OUTPUT"
: "${OHOS_CLT_URL:?Configure the official Huawei Linux x64 CLT URL with SDK API 22}"
: "${OHOS_CLT_SHA256:?Configure the official CLT SHA-256}"
python3 - <<'CHECK'
import os, re
from urllib.parse import urlsplit
url = urlsplit(os.environ['OHOS_CLT_URL'])
if url.scheme != 'https' or url.hostname not in {'dl.developer.huawei.com', 'developer.huawei.com', 'contentcenter-vali-drcn.dbankcdn.cn'} or url.username or url.password:
    raise SystemExit('CLT download must use an official Huawei HTTPS origin')
if not re.fullmatch('[a-f0-9]{64}', os.environ['OHOS_CLT_SHA256']):
    raise SystemExit('CLT SHA-256 must be an exact lowercase digest')
CHECK
if [[ ! -f "$TASK_OUTPUT/clt.zip" ]]; then
  curl --fail --location --proto '=https' --proto-redir '=https' --retry 3 "$OHOS_CLT_URL" -o "$TASK_OUTPUT/clt.zip"
fi
printf '%s  %s\n' "$OHOS_CLT_SHA256" "$TASK_OUTPUT/clt.zip" | sha256sum --check --strict
touch "$TASK_OUTPUT/CLT_VERIFIED"
unzip -q "$TASK_OUTPUT/clt.zip" -d "$TASK_OUTPUT/tools"
TASK_CLT="$TASK_OUTPUT/tools/command-line-tools"
export DEVECO_SDK_HOME="$TASK_CLT/sdk"
export DEVECO_NODE_HOME="$TASK_CLT/tool/node"
export PATH="$TASK_CLT/tool/node/bin:$TASK_CLT/bin:$PATH"
[[ -x "$TASK_CLT/tool/node/bin/node" && -f "$TASK_CLT/bin/hvigorw" ]] || {
  echo 'Official CLT tool layout missing' >&2; exit 2;
}
node --version
bash "$TASK_CLT/bin/hvigorw" --version
