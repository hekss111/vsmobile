package com.vsmobile.editor.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathRulesTest {

    @Test
    fun `обычный путь режется на сегменты`() {
        assertEquals(listOf("src", "main", "A.kt"), PathRules.split("src/main/A.kt"))
    }

    @Test
    fun `пустой путь означает корень проекта`() {
        assertEquals(emptyList<String>(), PathRules.split(""))
    }

    @Test
    fun `опасные пути отклоняются`() {
        listOf("../x", "a/../b", "/etc", "a//b", "a/./b", "a\\b", "/").forEach {
            assertNull(it, PathRules.split(it))
        }
    }

    @Test
    fun `имя без слешей безопасно`() {
        assertTrue(PathRules.isSafeName("new.txt"))
        assertFalse(PathRules.isSafeName("a/b"))
        assertFalse(PathRules.isSafeName(".."))
    }
}
