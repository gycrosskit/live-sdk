package com.tencent.imsdk.v2
class V2TIMManager private constructor() {
    var loginUser: String? = null
    var loginStatus: Int = 3
    companion object {
        const val V2TIM_STATUS_LOGINED = 1
        const val V2TIM_STATUS_LOGINING = 2
        private val instance = V2TIMManager()
        fun getInstance() = instance
    }
}
