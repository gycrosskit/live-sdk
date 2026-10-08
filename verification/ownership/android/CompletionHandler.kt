package io.trtc.tuikit.atomicxcore.api
interface CompletionHandler { fun onSuccess(); fun onFailure(code: Int, desc: String) }
