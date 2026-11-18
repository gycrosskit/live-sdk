#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${VERSION:?Pass an immutable released Maven version}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?$ ]] || { echo "Invalid release version" >&2; exit 1; }
output="$(mktemp -d)"
preserve_evidence() {
  status=$?
  diagnostics="${CI_DIAGNOSTICS_DIR:-ci-diagnostics}/public"
  mkdir -p "$diagnostics"
  printf 'version=%s\nexit=%s\n' "$VERSION" "$status" > "$diagnostics/result.txt"
  rm -rf "$output"
  exit "$status"
}
trap preserve_evidence EXIT
# 从不可变版本 Tag 取得提交和 tree，沿用 JitPack 的回执与归档校验。
repo_root="$PWD"
git init -q "$output/tag"
git -C "$output/tag" fetch -q --no-tags --depth=1 https://github.com/gycrosskit/live-sdk.git "refs/tags/$VERSION"
git -C "$output/tag" checkout -q --detach FETCH_HEAD
commit="$(git -C "$output/tag" rev-parse --verify HEAD)"
(cd "$output/tag" && bash "$repo_root/scripts/jitpack-install-prebuilt.sh" live-sdk "$output/maven")
native_targets=ios_arm64,ios_x64,ios_simulator_arm64,ohos_arm64
modules="$(python3 scripts/maven-publications.py "$VERSION" --modules)"
publications="$(python3 scripts/maven-publications.py "$VERSION")"
python3 scripts/check-maven.py "$output/maven" com.github.gycrosskit.live-sdk "$VERSION" "$modules" "$native_targets" "$publications"
# 消费方只使用 JitPack；归档校验不会安装到 MavenLocal 或替代远程解析。

# 完整预期来自版本合同；上面已核对归档的真实集合，不能由公网自报反推。
python3 scripts/check-public-maven.py --repo live-sdk --version "$VERSION" --commit "$commit" --expected-publications "$publications" --output-dir "${CI_DIAGNOSTICS_DIR:-ci-diagnostics}/public"
