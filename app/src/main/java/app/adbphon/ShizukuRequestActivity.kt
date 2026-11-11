package app.adbphon

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.os.Bundle
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Окно «Приложение просит права shell»: сервер Shizuku запускает его по действию app.adbphon.intent.action.REQUEST_PERMISSION. */
class ShizukuRequestActivity : Activity() {

    private fun reply(uid: Int, pid: Int, code: Int, allowed: Boolean, onetime: Boolean) {
        val data = Bundle().apply { putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed); putBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime) }
        runCatching { Shizuku.dispatchPermissionConfirmationResult(uid, pid, code, data) }.onFailure { AppLog.e("ADBphon", "shizuku result", it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuHost.init()
        val uid = intent.getIntExtra("uid", -1)
        val pid = intent.getIntExtra("pid", -1)
        val code = intent.getIntExtra("requestCode", -1)
        @Suppress("DEPRECATION") val ai = intent.getParcelableExtra<ApplicationInfo>("applicationInfo")
        if (uid == -1 || pid == -1 || ai == null) { finish(); return }
        // binder сервера мог ещё не дойти до нашего процесса
        if (!Shizuku.pingBinder()) {
            val latch = CountDownLatch(1)
            val l = Shizuku.OnBinderReceivedListener { latch.countDown() }
            Shizuku.addBinderReceivedListenerSticky(l)
            latch.await(5, TimeUnit.SECONDS)
            Shizuku.removeBinderReceivedListener(l)
        }
        val label = runCatching { ai.loadLabel(packageManager).toString() }.getOrDefault(ai.packageName)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.shz_req_title))
            .setMessage(getString(R.string.shz_req_text, label))
            .setCancelable(false)
            .setPositiveButton(R.string.shz_req_allow) { _, _ -> reply(uid, pid, code, allowed = true, onetime = false) }
            .setNeutralButton(R.string.shz_req_once) { _, _ -> reply(uid, pid, code, allowed = true, onetime = true) }
            .setNegativeButton(R.string.shz_req_deny) { _, _ -> reply(uid, pid, code, allowed = false, onetime = true) }
            .setOnDismissListener { finish() }
            .show()
    }
}
