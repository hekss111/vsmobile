package com.vsmobile.editor.plugins

object PermissionText {

    fun describe(permission: Permission): String = when (permission) {
        Permission.EDITOR -> "Читать и менять открытый файл"
        Permission.FILES -> "Читать, создавать и удалять файлы открытого проекта"
        Permission.NETWORK -> "Выходить в интернет"
        Permission.STORAGE -> "Хранить свои данные на телефоне"
        Permission.UI -> "Показывать окна, уведомления и свои панели"
        Permission.AI -> "Отправлять запросы вашему ИИ-помощнику (тратит ваш ключ)"
        Permission.PYTHON -> "Запускать Python-код в приложении"
        Permission.CLIPBOARD -> "Читать и менять буфер обмена"
    }

    fun warnings(requested: Set<Permission>): List<String> {
        val result = mutableListOf<String>()
        if (Permission.NETWORK in requested) {
            if (Permission.FILES in requested) {
                result += "Вместе эти права позволяют отправить файлы проекта в интернет"
            }
            if (Permission.EDITOR in requested) {
                result += "Вместе эти права позволяют отправить текст ваших файлов в интернет"
            }
            if (Permission.CLIPBOARD in requested) {
                result += "Вместе эти права позволяют отправить содержимое буфера обмена в интернет"
            }
        }
        return result
    }
}
