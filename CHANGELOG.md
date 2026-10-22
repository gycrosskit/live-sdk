# 更新记录

## 0.3.0-rc.1（RC 准备，尚未发布）

- 新增鸿蒙 Kuikly 观看源码候选及独立 HAR 接线，复用公共会话/预览/快照/点赞逻辑；不含连麦、PK、关注和 PiP；Kotlin/HAR 固定 SHA 编译记录见开发与验证，未做真机验收。
- 移除根 CMP UI 与根 Maven 产品，根工程仅聚合 `live-core` / `live-kuikly`。
- 保留原生会话、Kuikly raw / KuiklyCompose、共用测试和 iOS Pod 原版本。
- 当前 consumer / CI / 归档只验证两产品、12 个 publication；旧 tag 与 checksum 保留，重验旧版使用其原脚本。
- 原生配套固定为 Git tag `native-0.2.1-rc.15` / Pod `0.2.1-rc.15`，新增本轮 Maven 与该 Renderer 的最终链接门禁；尚未执行远程消费。
- 迁移与发布边界见 [RC 迁移说明](docs/RC迁移说明.md)。

## 0.2.1-rc.7（预发布）

- Kuikly View提供 PiP进入/实际状态同步，Android/iOS复用核心观看会话。
- 请求accepted与实际浮窗状态分开；iOS实验接口准备成功不再报告actual=true。
- Swift Kuikly换房清除尚未交付的旧房间事件。
- 当前官方Harmony SDK/协议差距、完整46文件覆盖与验证边界见[完整源码审查](docs/完整源码审查.md)。
- 冻结归档/不可变标签/Release重下载、全部JitPack文件、真实Git Pod SDK/App链接及新目录Maven最终消费通过；设备验证未执行，rc.6历史验收保留。
