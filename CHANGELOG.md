# 更新记录

## Unreleased

- Kuikly KMP 与宿主统一为 `2.29.1-2.0.21-ohos`，配套 iOS/OHOS Renderer 固定 `2.29.1`；同步配置与锁文件，后续随宿主升级。尚未发布，完整 SDK 与消费验证分别记录。

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
