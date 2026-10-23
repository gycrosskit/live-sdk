# 0.3.0-rc.3 RC 迁移说明

本 RC 使用 Maven `0.3.0-rc.3` / ohpm `@gycrosskit/live-native@0.3.0-rc.3`；真实归档摘要、固定源码 SHA、远程 CI 与公开消费结果由本轮发布收据记录。没有新 checksum 时发布校验直接失败，不复用旧版本或填写占位摘要。

本轮修正公共 `KuiklyLiveView` 提前注册事件的崩溃：事件工厂仅创建对象，待 Kuikly 注入 pagerId 并挂载 View 后注册 `nativeLiveEvent`。真实 Kuikly 初始化回归同时覆盖 Android/iOS/OHOS 的公共入口；HAR 与 iOS 原生实现不变，不重发对应渠道。源码 CI、公开 Maven 消费与宿主设备复验均须重新留证，不能套用 rc.2 的编译结论。

## 从旧 CMP 产品迁移

- 删除根 `live-sdk` CMP 产品和对应 CMP UI / consumer，属于不兼容变更；不能仅替换版本号升级旧 CMP 调用方。
- 旧 CMP 使用方继续锁定已发布的旧版本及其原 tag 文档，或先迁移到 Kuikly raw / KuiklyCompose / native 入口。KuiklyCompose 和 compiler / runtime 仍保留，宿主统一匹配 Kuikly / Renderer。
- 新版本只提供 `live-core` / `live-kuikly`，共 12 个 Maven publication；根聚合工程不补兼容空壳或伪 Maven 坐标。
- Git Pod `GycLiveNative` 固定 tag `native-0.2.1-rc.15` / Pod `0.2.1-rc.15`；新增 OHOS `live-kuikly` 变体与 `@gycrosskit/live-native@0.3.0-rc.3` HAR；HAR 已上架，Registry 精确安装与独立 ArkTS 编译已通过，公开消费以发布收据为准，设备尚未验收。编译证据见开发与验证，Maven RC 版本不能替代原生 tag 或 Pod 内部版本。

## 发布与验收边界

远程 macOS 按现有脚本打包，metadata 正规化后检查精确 publication、POM 许可、平台变体、实际文件与 MD5 / SHA-1 / SHA-256 / SHA-512 sidecar，保存真实 SHA-256 与源码身份。旧发布 tag、归档及 `release-checksums.txt` 既有行保持不变。

新 RC 只作为 prerelease。发布后按精确版本从 JitPack 验证完整公开 inventory / metadata / 文件与 Android、iOS、OHOS 实际受支持变体，iOS 按上面的独立原生版本组合消费；不使用 `mavenLocal`、staging 或 source substitution 充当公开消费。新坐标或 root metadata 指向的真实文件缺失必须失败，不回退旧 CMP 制品或旧版本。

组件源码与发布验证沿用远程 CI；宿主本地打包和设备操作按使用方本轮明确授权执行。CI、远程消费与真实设备 / SDK / 业务验收分别记录，设备安装启动不能代替直播验收。
