# 更新记录

## 1.1.2（HAR 候选）

- 补齐包内当前版本更新记录，修复 OHPM 提交时缺少当前版本 CHANGELOG 的拒绝。
- 延续上一版组件能力及宿主可选的 Kuikly Renderer 范围；API、厂商依赖与生命周期行为不变。
- 使用新不可变 HAR 版本，不覆盖旧标签或制品；构建、公开消费、审核与 Registry 安装分别验收，当前尚未发布。

## Maven 1.1.2 / Native 1.0.2 / HAR 1.1.1

- Android/OHOS 累计 SDK 消息列表按身份键去重并提取新增消息，保留有界快照、重连复用与成员事件。
- Renderer Pod/HAR 改为宿主可选 Kuikly 范围；构建基线仍为 2.29.1。Pod Core/Renderer 同用 `native-1.0.2`，Maven 与 HAR 独立版本化。
- 各渠道状态以精确 Release/Registry 及消费收据为准；历史标签、checksum 与设备验收记录保持原范围。

## 1.1.0（发布候选）

- 鸿蒙完整观看与静音预览统一使用 AtomicPlayer，保留进离房结算、关播释放和同会话小窗。
- Kuikly 新增快照/播放事件解码，以及带点赞 reporter 和额外房间字段的 LiveVideo；保留原六参数 API。
- Android 原生节点统一管理挂载与销毁时序；业务点赞房间、账号及成功尾批仍由宿主决定。
- Maven 与 HAR/OHPM 为 1.1.0；Swift 运行时代码不变，iOS 原生 Pod 沿用 native-1.0.1。
- 构建模型对齐固定 CLT 26，目标 API 22 不变；公开坐标消费和真机验收分别记录。

## 1.0.1 - 2026-10-25

- 为本轮最新 dev 配套候选使用新版本，保留既有 1.0.0 标签和制品摘要；源码验证、制品生产与公开消费重新执行，当前尚未发布。

## 1.0.0（未发布）

- Maven、iOS Pod 与 OHPM 统一版本为 1.0.0；同仓模块使用同版，Kuikly 配套保持 2.29.1。
- 本地源码/产物消费与设备、远程发布验收分别记录；不覆盖旧标签或修改历史发布字节。

## 0.3.0-rc.2（预发行）

- 新增鸿蒙 Kuikly 观看及配套 `@gycrosskit/live-native@0.3.0-rc.3` HAR，复用公共会话、预览、快照和点赞逻辑；不含连麦、PK、关注和 PiP。
- Maven 新增 `live-kuikly-ohosarm64`，两产品共 12 个 publication；Android/iOS 平台源码与 SDK 版本不变。
- iOS Git Pod 沿用 `native-0.2.1-rc.15`，不空发原生版本。
- Kotlin/HAR 完整源码 CI 已通过；本版本的归档摘要、发布与精确远程消费由发布收据记录。真机音画未验收。

## 0.3.0-rc.1（已发布）

- 移除根 CMP UI 与根 Maven 产品，仅保留 `live-core` / `live-kuikly`，共 11 个 publication；KuiklyCompose 及原生会话保留。
- OHOS 仅包含 Core KLIB，不包含本次新增的 Kuikly 鸿蒙观看接线。
- iOS Git Pod 配套 `native-0.2.1-rc.15`；历史制品与校验值保持不变。

## 0.2.1-rc.7（预发布）

- Kuikly View提供 PiP进入/实际状态同步，Android/iOS复用核心观看会话。
- 请求accepted与实际浮窗状态分开；iOS实验接口准备成功不再报告actual=true。
- Swift Kuikly换房清除尚未交付的旧房间事件。
- 当前官方Harmony SDK/协议差距、完整46文件覆盖与验证边界见[完整源码审查](docs/完整源码审查.md)。
- 冻结归档/不可变标签/Release重下载、全部JitPack文件、真实Git Pod SDK/App链接及新目录Maven最终消费通过；设备验证未执行，rc.6历史验收保留。
