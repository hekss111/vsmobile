package com.vsmobile.editor

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

data class AccentOption(val name: String, val color: Color)

data class BackgroundOption(
    val name: String,
    val background: Color,
    val surface: Color,
    val divider: Color
)

object AppTheme {

    val accents = listOf(
        AccentOption("Синий", Color(0xFF569CD6)),
        AccentOption("Бирюзовый", Color(0xFF4EC9B0)),
        AccentOption("Зелёный", Color(0xFF7EC27E)),
        AccentOption("Жёлтый", Color(0xFFDCDCAA)),
        AccentOption("Оранжевый", Color(0xFFE08A3C)),
        AccentOption("Красный", Color(0xFFE06C6C)),
        AccentOption("Розовый", Color(0xFFE06C9F)),
        AccentOption("Фиолетовый", Color(0xFFB48EE0))
    )

    val backgrounds = listOf(
        BackgroundOption("Dark+", Color(0xFF1E1E1E), Color(0xFF252526), Color(0xFF3C3C3C)),
        BackgroundOption("AMOLED", Color(0xFF000000), Color(0xFF121212), Color(0xFF2A2A2A)),
        BackgroundOption("Графит", Color(0xFF1B1F23), Color(0xFF24292E), Color(0xFF3A4047)),
        BackgroundOption("Ночь", Color(0xFF0F1B2D), Color(0xFF16263D), Color(0xFF24395A)),
        BackgroundOption("Лес", Color(0xFF0F1F1A), Color(0xFF17302A), Color(0xFF24473E)),
        BackgroundOption("Бордо", Color(0xFF1E1416), Color(0xFF2B1C20), Color(0xFF4A2F36))
    )

    private val defaultAccent = Color(0xFF569CD6)
    private const val prefsName = "vsmobile"
    private const val accentKey = "theme_accent"
    private const val backgroundKey = "theme_background"

    var accent by mutableStateOf(defaultAccent)
        private set

    var backgroundIndex by mutableStateOf(0)
        private set

    private var loaded = false

    val background: Color
        get() = backgrounds[backgroundIndex].background

    val surface: Color
        get() = backgrounds[backgroundIndex].surface

    val divider: Color
        get() = backgrounds[backgroundIndex].divider

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (prefs.contains(accentKey)) {
            accent = Color(prefs.getInt(accentKey, defaultAccent.toArgb()))
        }
        backgroundIndex = prefs.getInt(backgroundKey, 0).coerceIn(0, backgrounds.lastIndex)
    }

    fun setAccent(context: Context, color: Color) {
        accent = color
        context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putInt(accentKey, color.toArgb())
            .apply()
    }

    /** Меняет акцент «вживую» (пока двигаем ползунок), без записи на диск. */
    fun previewAccent(color: Color) {
        accent = color
    }

    fun setBackground(context: Context, index: Int) {
        backgroundIndex = index.coerceIn(0, backgrounds.lastIndex)
        context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putInt(backgroundKey, backgroundIndex)
            .apply()
    }

    fun reset(context: Context) {
        accent = defaultAccent
        backgroundIndex = 0
        context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .remove(accentKey)
            .remove(backgroundKey)
            .apply()
    }

    fun hexOf(color: Color): String = "%06X".format(color.toArgb() and 0xFFFFFF)

    fun parseHex(text: String): Color? {
        val t = text.trim().removePrefix("#")
        if (t.length != 6) return null
        val v = t.toLongOrNull(16) ?: return null
        return Color((0xFF000000L or v).toInt())
    }

    fun colorScheme(): ColorScheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF111111),
        secondary = accent,
        background = background,
        surface = surface,
        onBackground = Color(0xFFD4D4D4),
        onSurface = Color(0xFFD4D4D4)
    )
}
