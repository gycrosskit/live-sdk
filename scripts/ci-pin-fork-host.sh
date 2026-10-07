#!/usr/bin/env bash
set -euo pipefail
# 只在临时 GitHub runner 复用本 job 已经通过原域名 HTTPS 验证的地址。
if [[ "${GITHUB_ACTIONS:-}" != true || ! "${RUNNER_OS:-}" =~ ^(Linux|macOS)$ ]]; then
    echo "CI fork host reuse skipped outside GitHub runner"
    exit 0
fi
ci_dns_host=maven.eazytec-cloud.com
ci_probe_dir="$(mktemp -d)"
trap 'rm -rf "$ci_probe_dir"' EXIT
ci_marker_url="https://$ci_dns_host/nexus/repository/maven-public/org/jetbrains/kotlin/multiplatform/org.jetbrains.kotlin.multiplatform.gradle.plugin/2.2.21-1.0.0/org.jetbrains.kotlin.multiplatform.gradle.plugin-2.2.21-1.0.0.pom"
for ci_attempt in 1 2 3; do
    # 不 follow redirect；remote_ip 必须来自原 HTTPS 主机，而非代理或别的域名。
    if ci_probe="$(curl --disable --fail --silent --show-error --proto '=https' --ipv4 --noproxy "$ci_dns_host" \
        --connect-timeout 20 --max-time 60 --output "$ci_probe_dir/marker.pom" \
        --write-out '%{http_code} %{remote_ip}' "$ci_marker_url")"; then
        if ci_address="$(python3 - "$ci_probe" <<'PYTHON'
import ipaddress, sys
parts = sys.argv[1].split()
if len(parts) != 2 or parts[0] != '200':
    sys.exit(1)
try:
    address = ipaddress.IPv4Address(parts[1])
except ipaddress.AddressValueError:
    sys.exit(1)
if not address.is_global or address.is_multicast:
    sys.exit(1)
print(address)
PYTHON
        )"; then
            printf '%s %s\n' "$ci_address" "$ci_dns_host" | sudo tee -a /etc/hosts >/dev/null
            echo "CI fork HTTPS verified; job host address reused: $ci_dns_host $ci_address"
            exit 0
        fi
    fi
    echo "CI fork HTTPS address probe failed (attempt $ci_attempt/3)"
    if [[ "$ci_attempt" != 3 ]]; then sleep 2; fi
done
# 不能把探测替代为验收门禁；真实 Gradle 下载/编译仍决定成败。
echo "CI fork host reuse unavailable; continuing normal Gradle resolution"
