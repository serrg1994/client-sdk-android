package io.livekit.android

import io.livekit.android.util.LKLog
import org.webrtc.EglBase

/*
* The purpose of shared EglBase it's sharing video tracks between multiple rooms
*  */
object EglBaseShared {
    private var counter = 0
    private var eglBase: EglBase? = null

    @Synchronized
    fun create(): EglBase {
        if (eglBase == null) {
            eglBase = EglBase.create()
        }
        counter += 1
        LKLog.d { "Create shared EglBase" }
        return eglBase!!
    }

    @Synchronized
    fun release() {
        counter -= 1

        if (counter <= 0) {
            counter = 0
            LKLog.d { "Dispose shared EglBase" }
            eglBase?.release()
            eglBase = null
        }
    }
}
