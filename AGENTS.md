# live-sdk 工作规则

## Code Review Rules

- 评审结论、问题标题、影响与建议使用简体中文，技术标识符、路径、API 名称和严重级别保留原文。只执行评审，不自动修复、提交、推送或合并；未确认重大问题时，用中文说明结果与未验证范围。
- 以本次实际 base/head 为准，沿变更涉及的公开入口和调用链，只报告本次引入且有实际影响的 P0/P1 问题，写明触发输入或时序、源码位置、影响与违反的合同。已有问题、风格偏好、无具体失败场景的假设及 CI 已覆盖的机械检查不列为本次缺陷；没有确认问题就不凑发现项。
- 公共 API 或平台桥接变更须核对实际调用方的参数、默认值、结果、错误与 Unsupported 合同，结合当前源码和构建配置判断平台能力，不强求平台对称。组件负责通用能力，宿主负责业务和接线；Mock、编译、CI、真实 SDK/设备与远程消费分别判断，缺少验收证据本身不等于已证实的缺陷。
- 涉及 AtomicX 观看、预览或账号切换时，沿 AtomicAudienceSession / AtomicAudienceSessionCoordinator 检查 Main 串行、进离房结算、token 和 generation 隔离：旧 leave、迟到 SDK 回调或旧预览清理不能影响新房间，release 不得提前放行共享 Store。prepared 与 ownsRuntime 分开判断，不能清理借用的 IM 身份；PiP 请求受理不能冒充实际状态。
