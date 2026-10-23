"""发布消费者仅复用依赖下载缓存，组件坐标仍为 remoteOnly，测试必须执行。"""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
workflow = (root / '.github/workflows/release-validation.yml').read_text()
job = workflow.split('  release-android:\n', 1)[1].split('  release-native:\n', 1)[0]
assert job.count('uses: gradle/actions/setup-gradle@') == 1
assert 'cache-read-only: true' in job and 'cache: gradle' not in job
assert 'GYC_CI_BUILD_CACHE' not in job
assert '-PremoteOnly' in job and '-PverifyLocalSource' not in job
assert "assert len(cases) == 2" in job and "('failure', 'error', 'skipped')" in job
print('PASS: one read-only dependency cache, remoteOnly products, two actually executed tests required')
