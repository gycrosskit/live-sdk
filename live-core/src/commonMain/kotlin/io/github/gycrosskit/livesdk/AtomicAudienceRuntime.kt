package io.github.gycrosskit.livesdk

/** AtomicX 观看组件向双端宿主请求日志和短提示的最小桥接。 */
interface AtomicAudienceRuntime {
    /** 记录组件运行信息；宿主负责持久化和 Release 环境输出策略。 */
    fun log(level: AtomicAudienceLogLevel, message: String, error: Throwable? = null)

    /** 展示不需要用户决策的短暂提示；宿主决定 Toast 或 iOS Banner 形式。 */
    fun showMessage(message: String)
}

/** 宿主日志桥可消费的稳定级别，不映射具体日志库类型。 */
enum class AtomicAudienceLogLevel { INFO, WARNING, ERROR }

/**
 * 进程级运行时桥注册表。
 *
 * `live-sdk` 不直接依赖 App 的 Logger、Android Toast 或 iOS Banner；宿主未安装实现时安全忽略输出，
 * 避免组件自行向 Release 系统日志写入业务信息。
 */
object AtomicAudienceRuntimeRegistry {
    private var runtime: AtomicAudienceRuntime? = null

    /** 安装或替换进程级宿主桥，应在创建任何直播控件前调用。 */
    fun install(value: AtomicAudienceRuntime) {
        runtime = value
    }

    internal fun info(message: String) {
        runtime?.log(AtomicAudienceLogLevel.INFO, message)
    }

    internal fun warning(message: String, error: Throwable? = null) {
        runtime?.log(AtomicAudienceLogLevel.WARNING, message, error)
    }

    internal fun error(message: String, error: Throwable? = null) {
        runtime?.log(AtomicAudienceLogLevel.ERROR, message, error)
    }

    internal fun showMessage(message: String) {
        runtime?.showMessage(message)
    }
}
