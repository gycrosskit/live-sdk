# 0.3.0-rc.4 RC 迁移说明

> 当前迁移目标已统一为 Maven / iOS Pod / OHPM `1.0.0`（未发布），Kuikly 2.29.1。下文 RC 编号仅说明此前迁移边界，不是当前配套版本或新版本验收证明。

本 RC 使用 Maven `0.3.0-rc.4` / ohpm `@gycrosskit/live-native@0.3.0-rc.4`；真实归档摘要、固定源码 SHA、远程 CI 与公开消费结果由本轮发布收据记录。没有新 checksum 时发布校验直接失败，不复用旧版本或填写占位摘要。

本轮包含公共 `KuiklyLiveView` 挂载后注册事件、鸿蒙主 TRTC 观看绑定、成员消息及系统 PiP 修复。宿主提供根账号生命周期和所属 WindowStage 策略；单 Ability 接入时保留 Root/Audience 双 Renderer，并设置 `pictureInPictureHostManaged=true`，不另启直播 Ability；新 WebView HAR `0.2.0-rc.15` 提供窗口策略注入，Maven/iOS Pod 不随其空发版。Live iOS Native 源码不变，保留 `native-0.2.1-rc.15`。单 Ability 分项包已有音画、PiP、主页操作与注销的用户验收；整套 Module 下沉候选设备回归待统一执行；新远程版本消费和升级后宿主回归仍分别验收。

## 从旧 CMP 产品迁移

- 删除根 `live-sdk` CMP 产品和对应 CMP UI / consumer，属于不兼容变更；不能仅替换版本号升级旧 CMP 调用方。
- 旧 CMP 使用方继续锁定已发布的旧版本及其原 tag 文档，或先迁移到 Kuikly raw / KuiklyCompose / native 入口。KuiklyCompose 和 compiler / runtime 仍保留，宿主统一匹配 Kuikly / Renderer。
- 新版本只提供 `live-core` / `live-kuikly`，共 12 个 Maven publication；根聚合工程不补兼容空壳或伪 Maven 坐标。
- Git Pod `GycLiveNative` 固定 tag `native-0.2.1-rc.15` / Pod `0.2.1-rc.15`；新增 OHOS `live-kuikly` 变体与 `@gycrosskit/live-native@0.3.0-rc.4` HAR；新 HAR 的 Registry 精确安装、独立 ArkTS 编译及宿主升级回归以 rc.4 收据为准，旧 rc.3 的结果不沿用。编译证据见开发与验证，Maven RC 版本不能替代原生 tag 或 Pod 内部版本。

## 发布与验收边界

远程 macOS 按现有脚本打包，metadata 正规化后检查精确 publication、POM 许可、平台变体、实际文件与 MD5 / SHA-1 / SHA-256 / SHA-512 sidecar，保存真实 SHA-256 与源码身份。旧发布 tag、归档及 `release-checksums.txt` 既有行保持不变。

新 RC 只作为 prerelease。发布后按精确版本从 JitPack 验证完整公开 inventory / metadata / 文件与 Android、iOS、OHOS 实际受支持变体，iOS 按上面的独立原生版本组合消费；不使用 `mavenLocal`、staging 或 source substitution 充当公开消费。新坐标或 root metadata 指向的真实文件缺失必须失败，不回退旧 CMP 制品或旧版本。

组件源码与发布验证沿用远程 CI；宿主本地打包和设备操作按使用方本轮明确授权执行。CI、远程消费与真实设备 / SDK / 业务验收分别记录，设备安装启动不能代替直播验收。
