package com.vsmobile.editor

/** Ссылки автора и флаги релиза v1.0. */
object AppLinks {
    const val TELEGRAM = "https://t.me/k1llcoder_ai"
    const val GITHUB = "https://github.com/hekss111"

    /**
     * Вход в аккаунт и оплата подписки в v1.0 недоступны. Чтобы включить, поставьте true:
     * экран настроек снова покажет кнопки «Войти» и «Подписка», остальной код уже готов.
     */
    const val ACCOUNT_AVAILABLE = false

    /** Как часто показывать окно «Не потеряй нас» (пока приложение на экране). */
    const val PROMO_INTERVAL_MS = 10 * 60 * 1000L
}
