#!/usr/bin/env bash
set -euo pipefail

mode="${1:?create or cleanup}"
record="${2:?ownership record}"
owner="${3:?invocation owner}"
uuid_pattern='^[0-9A-Fa-f]{8}-([0-9A-Fa-f]{4}-){3}[0-9A-Fa-f]{12}$'
[[ "$owner" =~ $uuid_pattern ]] || { echo 'Invalid invocation owner' >&2; exit 1; }

if [[ "$mode" == cleanup ]]; then
  [[ -e "$record" || -L "$record" ]] || exit 0
  [[ -f "$record" && ! -L "$record" ]] || { echo 'Invalid Simulator record' >&2; exit 1; }
  { IFS= read -r device; IFS= read -r recorded_owner; } < "$record"
  [[ "$device" =~ $uuid_pattern && "$recorded_owner" == "$owner" ]] || {
    echo 'Refusing to clean a Simulator belonging to another invocation' >&2; exit 1;
  }
  expected="$(printf '%s\n%s' "$device" "$owner")"
  [[ "$(cat "$record")" == "$expected" ]] || { echo 'Invalid Simulator record' >&2; exit 1; }
  xcrun simctl shutdown "$device" >/dev/null 2>&1 || true
  xcrun simctl delete "$device"
  [[ "$(cat "$record")" == "$expected" ]] || { echo 'Simulator record changed during cleanup' >&2; exit 1; }
  rm -- "$record"
  exit 0
fi

[[ "$mode" == create ]] || { echo 'Expected create or cleanup' >&2; exit 1; }
[[ ! -e "$record" && ! -L "$record" ]] || { echo 'Unresolved Simulator record; refusing to overwrite' >&2; exit 1; }
mkdir -p "$(dirname "$record")"
pending="$(mktemp "${record}.pending.XXXXXX")"
device=''
published=false
finish() {
  local status=$?
  if [[ "$published" == false && "$device" =~ $uuid_pattern ]]; then
    xcrun simctl shutdown "$device" >/dev/null 2>&1 || true
    if ! xcrun simctl delete "$device"; then
      printf '%s\n%s\n' "$device" "$owner" > "$pending" || true
      ln "$pending" "$record" 2>/dev/null || true
      echo "Simulator cleanup failed; retained ownership record: $pending" >&2
      exit 1
    fi
  fi
  rm -f -- "$pending"
  exit "$status"
}
trap finish EXIT

read -r device_type runtime <<< "$(xcrun simctl list devices available --json | python3 -c '
import json, re, sys
runtimes = json.load(sys.stdin)["devices"]
for runtime in sorted(runtimes, key=lambda value: tuple(map(int, re.findall(r"[0-9]+", value))), reverse=True):
    if "iOS" in runtime:
        for item in runtimes[runtime]:
            if item["name"].startswith("iPhone"):
                print(item["deviceTypeIdentifier"], runtime)
                sys.exit(0)
sys.exit("No available iPhone Simulator type")')"
[[ -n "$device_type" && -n "$runtime" ]] || { echo 'No available iPhone Simulator type' >&2; exit 1; }
device="$(xcrun simctl create "GYC Live KGP $owner" "$device_type" "$runtime")"
[[ "$device" =~ $uuid_pattern ]] || { echo 'Simulator creation returned an invalid ID' >&2; exit 1; }
printf '%s\n%s\n' "$device" "$owner" > "$pending"
# 同目录硬链接原子发布完整记录，已有未决记录不能被覆盖。
ln "$pending" "$record"
published=true
