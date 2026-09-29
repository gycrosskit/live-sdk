# AtomicX 同时包含可选的 TUICore Push 适配代码，当前观看端未接入该能力。
# 仅忽略未使用的可选类型，其他缺失类仍由 R8 阻断。
-dontwarn com.tencent.qcloud.tuicore.TUICore
-dontwarn com.tencent.qcloud.tuicore.interfaces.TUIServiceCallback
