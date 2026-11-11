package app.adbphon

import android.os.Bundle
import moe.shizuku.api.BinderContainer
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.server.ktx.workerHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Принимает binder сервера Shizuku (метод sendBinder в базовом классе) и binder пользовательских служб (sendUserService). */
class ShizukuManagerProvider : ShizukuProvider() {

    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        ShizukuHost.init()
        return super.onCreate()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (extras == null) return null
        if (method != "sendUserService") return super.call(method, arg, extras)
        return try {
            extras.classLoader = BinderContainer::class.java.classLoader
            val token = extras.getString(USER_SERVICE_ARG_TOKEN) ?: return null
            val binder = extras.getParcelable<BinderContainer>("moe.shizuku.privileged.api.intent.extra.BINDER")?.binder ?: return null
            val latch = CountDownLatch(1)
            var reply: Bundle? = Bundle()
            val listener = object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    try {
                        Shizuku.attachUserService(binder, Bundle().apply { putString(USER_SERVICE_ARG_TOKEN, token) })
                        reply!!.putParcelable("moe.shizuku.privileged.api.intent.extra.BINDER", BinderContainer(Shizuku.getBinder()))
                    } catch (e: Throwable) { AppLog.e("ADBphon", "attachUserService $token", e); reply = null }
                    Shizuku.removeBinderReceivedListener(this)
                    latch.countDown()
                }
            }
            Shizuku.addBinderReceivedListenerSticky(listener, workerHandler)
            latch.await(5, TimeUnit.SECONDS)
            reply
        } catch (e: Throwable) { AppLog.e("ADBphon", "sendUserService", e); null }
    }
}
