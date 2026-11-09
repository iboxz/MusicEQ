package com.boxeq.shizuku

import android.content.Context
import android.media.AudioPlaybackConfiguration
import android.os.IBinder
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** One active player as reported by the audio service. */
data class RawPlayback(val sessionId: Int, val clientUid: Int)

/**
 * The ONLY place that touches hidden Android APIs. Calls IAudioService through Shizuku's binder wrapper so
 * the request runs with shell privileges; without them the framework anonymises other apps' players
 * (uid/session come back as -1). Everything is reflective and every failure is returned, never thrown.
 *
 * Note: AudioPlaybackConfiguration only carries a session ID on Android 12+ (API 31+).
 */
object HiddenAudioApi {
    private class Handles(
        val service: Any,
        val getActive: Method,
        val getUid: Method,
        val getSession: Method,
    )

    @Volatile
    private var handles: Handles? = null

    fun readActivePlayback(): Result<List<RawPlayback>> = try {
        val h = handles ?: resolve().also { handles = it }
        val list = h.getActive.invoke(h.service) as? List<*> ?: emptyList<Any>()
        val out = list.filterNotNull().map {
            RawPlayback(
                sessionId = (h.getSession.invoke(it) as? Int) ?: -1,
                clientUid = (h.getUid.invoke(it) as? Int) ?: -1,
            )
        }
        if (out.isNotEmpty() && out.all { it.clientUid < 0 }) {
            error("playback info is anonymised (shell lacks MODIFY_AUDIO_ROUTING on this ROM)")
        }
        Result.success(out)
    } catch (t: Throwable) {
        handles = null // binder may have died; re-resolve next time
        val cause = (t as? InvocationTargetException)?.targetException ?: t
        Result.failure(IllegalStateException(cause.message ?: cause.javaClass.simpleName, cause))
    }

    private fun resolve(): Handles {
        check(Shizuku.pingBinder()) { "Shizuku is not running" }
        runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/media/") }

        val rawBinder: IBinder = SystemServiceHelper.getSystemService(Context.AUDIO_SERVICE)
            ?: error("audio service not available")
        val stub = Class.forName("android.media.IAudioService\$Stub")
        val service = stub.getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(rawBinder)) ?: error("IAudioService proxy is null")
        val iface = Class.forName("android.media.IAudioService")

        val cfg = AudioPlaybackConfiguration::class.java
        val getSession = findMethod(cfg, "getSessionId")
            ?: error("AudioPlaybackConfiguration has no session ID on this Android version (needs Android 12+)")
        val getUid = findMethod(cfg, "getClientUid") ?: error("getClientUid not available")
        return Handles(
            service = service,
            getActive = iface.getMethod("getActivePlaybackConfigurations"),
            getUid = getUid,
            getSession = getSession,
        )
    }

    private fun findMethod(c: Class<*>, name: String): Method? = try {
        c.getDeclaredMethod(name).also { it.isAccessible = true }
    } catch (e: Exception) {
        null
    }
}
