package app.adbphon

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Возврат из браузера (ссылка «ADB-phon» на странице «Готово» после входа в облако): сам окна не показывает, поднимает программу в её СОБСТВЕННОЙ задаче
 * (NEW_TASK + CLEAR_TOP), а не поверх задачи браузера. Тогда «Выход» и «Назад» не возвращают на страницу «Готово» в браузере.
 */
class ReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        finish()
    }
}
