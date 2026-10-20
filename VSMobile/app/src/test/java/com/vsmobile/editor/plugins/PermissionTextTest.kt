package com.vsmobile.editor.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionTextTest {

    @Test
    fun `у каждого права есть описание`() {
        Permission.values().forEach { assertTrue(PermissionText.describe(it).isNotBlank()) }
    }

    @Test
    fun `files и network вместе дают предупреждение об отправке файлов`() {
        val warnings = PermissionText.warnings(setOf(Permission.FILES, Permission.NETWORK))
        assertTrue(warnings.any { it.contains("файлы проекта") })
    }

    @Test
    fun `без network предупреждений нет`() {
        assertEquals(emptyList<String>(), PermissionText.warnings(setOf(Permission.FILES, Permission.EDITOR)))
    }
}
