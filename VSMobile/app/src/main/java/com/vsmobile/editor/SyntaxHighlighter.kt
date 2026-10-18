package com.vsmobile.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

object SyntaxHighlighter {

    val background: Color get() = AppTheme.background
    val gutterBg: Color get() = AppTheme.background
    val gutterText = Color(0xFF6E7681)
    val defaultText = Color(0xFFD4D4D4)
    val keywordColor = Color(0xFF569CD6)
    val stringColor = Color(0xFFCE9178)
    val commentColor = Color(0xFF6A9955)
    val numberColor = Color(0xFFB5CEA8)
    val functionColor = Color(0xFFDCDCAA)
    val annotationColor = Color(0xFFC586C0)
    val errorBg = Color(0x33FF5252)

    private val kotlinKeywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while",
        "do", "return", "break", "continue", "package", "import", "private", "public", "protected",
        "internal", "override", "open", "abstract", "final", "companion", "init", "constructor",
        "this", "super", "is", "as", "in", "out", "null", "true", "false", "try", "catch", "finally",
        "throw", "suspend", "inline", "reified", "vararg", "lateinit", "by", "get", "set", "data",
        "sealed", "enum", "annotation", "typealias", "where", "it", "field"
    )

    private val pythonKeywords = setOf(
        "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from", "as",
        "try", "except", "finally", "raise", "with", "lambda", "pass", "break", "continue", "global",
        "nonlocal", "yield", "in", "is", "not", "and", "or", "None", "True", "False", "self", "async", "await"
    )

    private val jsKeywords = setOf(
        "function", "const", "let", "var", "if", "else", "for", "while", "do", "return", "class",
        "extends", "new", "this", "super", "import", "export", "default", "from", "try", "catch",
        "finally", "throw", "async", "await", "yield", "typeof", "instanceof", "in", "of", "null",
        "undefined", "true", "false", "switch", "case", "break", "continue", "interface", "type"
    )

    private val cKeywords = setOf(
        "int", "float", "double", "char", "void", "if", "else", "for", "while", "do", "return",
        "struct", "typedef", "sizeof", "static", "const", "unsigned", "signed", "long", "short",
        "break", "continue", "switch", "case", "default", "include", "define", "class", "public",
        "private", "protected", "namespace", "using", "new", "delete", "template", "virtual"
    )

    fun keywordsFor(language: String): Set<String> = when (language) {
        "kt", "kts" -> kotlinKeywords
        "py" -> pythonKeywords
        "js", "ts", "jsx", "tsx" -> jsKeywords
        "c", "cpp", "h", "hpp", "java" -> cKeywords
        else -> emptySet()
    }

    fun languageFromFileName(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    private val numberRegex = Regex("\\b\\d+(\\.\\d+)?[fFLl]?\\b")
    private val annotationRegex = Regex("@\\w+")
    private val functionCallRegex = Regex("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=\\()")
    private val wordRegex = Regex("[a-zA-Z_][a-zA-Z0-9_]*")

    // Строки и комментарии ищем одним проходом слева направо; строка не переходит на новую строку.
    private val cLikeTokenRegex =
        Regex("""//.*|/\*[\s\S]*?\*/|"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'""")
    private val pyTokenRegex =
        Regex("""#.*|"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'""")

    fun highlight(text: String, language: String): AnnotatedString {
        val keywords = keywordsFor(language)
        val builder = AnnotatedString.Builder(text)

        fun applyAll(regex: Regex, style: SpanStyle) {
            for (m in regex.findAll(text)) {
                builder.addStyle(style, m.range.first, m.range.last + 1)
            }
        }

        builder.addStyle(SpanStyle(color = defaultText), 0, text.length)
        applyAll(numberRegex, SpanStyle(color = numberColor))
        applyAll(functionCallRegex, SpanStyle(color = functionColor))

        for (m in wordRegex.findAll(text)) {
            val w = m.value
            if (w in keywords) {
                builder.addStyle(
                    SpanStyle(color = keywordColor, fontWeight = FontWeight.Medium),
                    m.range.first, m.range.last + 1
                )
            }
        }

        if (language != "py") {
            applyAll(annotationRegex, SpanStyle(color = annotationColor))
        }

        // «//» внутри строки — не комментарий, а апостроф внутри комментария — не начало строки.
        val commentStyle = SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)
        val stringStyle = SpanStyle(color = stringColor)
        val tokenRegex = if (language == "py") pyTokenRegex else cLikeTokenRegex
        for (m in tokenRegex.findAll(text)) {
            val first = m.value[0]
            val style = if (first == '"' || first == '\'') stringStyle else commentStyle
            builder.addStyle(style, m.range.first, m.range.last + 1)
        }

        return builder.toAnnotatedString()
    }

    // --- Простая проверка ошибок --------------------------------------------------------------

    /** Языки, для которых вообще ищем ошибки. Для txt/md/json/xml и т.п. ничего не подсвечиваем. */
    private val errorCheckedLanguages = setOf(
        "kt", "kts", "py", "js", "ts", "jsx", "tsx", "java", "c", "cpp", "h", "hpp"
    )

    private val pyBlockStart = Regex(
        "^(if|elif|while|for|def|class|with|except|async\\s+def|async\\s+for|async\\s+with)\\b.*"
    )
    private val pyBareBlock = Regex("^(else|try|finally)$")

    /**
     * Возвращает номера строк (с нуля) с явными ошибками: незакрытая или лишняя скобка,
     * незакрытая строка, в Python — блок (if/for/def/...) без двоеточия.
     * Разбор учитывает строки и комментарии и работает по всему тексту, поэтому скобка,
     * открытая в одной строке и закрытая в другой, ошибкой не считается.
     */
    fun findErrorLines(text: String, language: String): Set<Int> {
        if (language !in errorCheckedLanguages) return emptySet()

        val isPy = language == "py"
        val isKotlin = language == "kt" || language == "kts"
        val hasBackticks = language == "js" || language == "ts" || language == "jsx" || language == "tsx"
        val reportUnterminated = language != "jsx" && language != "tsx"

        val errors = mutableSetOf<Int>()
        val stack = ArrayList<Pair<Char, Int>>()
        val code = StringBuilder()
        val n = text.length
        var line = 0
        var i = 0

        fun finishLine(depthZero: Boolean) {
            if (isPy && depthZero) {
                val c = code.toString().trim()
                if (c.isNotEmpty() && !c.endsWith("\\")) {
                    if (pyBareBlock.matches(c)) {
                        errors.add(line)
                    } else if (pyBlockStart.matches(c) && !c.contains(':')) {
                        errors.add(line)
                    }
                }
            }
            code.setLength(0)
        }

        while (i < n) {
            val ch = text[i]

            if (ch == '\n') {
                finishLine(stack.isEmpty())
                line++
                i++
                continue
            }

            // Комментарии
            if (isPy) {
                if (ch == '#') {
                    while (i < n && text[i] != '\n') i++
                    continue
                }
            } else if (ch == '/' && i + 1 < n) {
                val next = text[i + 1]
                if (next == '/') {
                    while (i < n && text[i] != '\n') i++
                    continue
                }
                if (next == '*') {
                    i += 2
                    while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) {
                        if (text[i] == '\n') line++
                        i++
                    }
                    i += 2
                    continue
                }
            }

            // Строки
            val isQuote = ch == '"' || ch == '\'' || (ch == '`' && hasBackticks)
            if (isQuote) {
                val q = ch
                val startLine = line
                val canBeTriple = (isPy && (q == '"' || q == '\'')) || (isKotlin && q == '"')
                val triple = canBeTriple && i + 2 < n && text[i + 1] == q && text[i + 2] == q

                if (triple) {
                    i += 3
                    var closed = false
                    while (i < n) {
                        val c = text[i]
                        if (c == '\\' && isPy) {
                            if (i + 1 < n && text[i + 1] == '\n') line++
                            i += 2
                            continue
                        }
                        if (c == q && i + 2 < n && text[i + 1] == q && text[i + 2] == q) {
                            i += 3
                            closed = true
                            break
                        }
                        if (c == '\n') line++
                        i++
                    }
                    if (!closed && reportUnterminated) errors.add(startLine)
                } else {
                    i++
                    var closed = false
                    while (i < n) {
                        val c = text[i]
                        if (c == '\\') {
                            if (i + 1 < n && text[i + 1] == '\n') line++
                            i += 2
                            continue
                        }
                        if (isKotlin && q == '"' && c == '$' && i + 1 < n && text[i + 1] == '{') {
                            var depth = 0
                            while (i < n && text[i] != '\n') {
                                if (text[i] == '{') {
                                    depth++
                                } else if (text[i] == '}') {
                                    depth--
                                    if (depth == 0) {
                                        i++
                                        break
                                    }
                                }
                                i++
                            }
                            continue
                        }
                        if (c == q) {
                            closed = true
                            i++
                            break
                        }
                        if (c == '\n') {
                            if (q == '`') {
                                line++
                                i++
                                continue
                            }
                            break
                        }
                        i++
                    }
                    if (!closed && reportUnterminated) errors.add(startLine)
                }
                code.append('S')
                continue
            }

            // Скобки
            when (ch) {
                '(', '[', '{' -> stack.add(ch to line)
                ')', ']', '}' -> {
                    val open = when (ch) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    if (stack.isNotEmpty() && stack.last().first == open) {
                        stack.removeAt(stack.size - 1)
                    } else {
                        errors.add(line)
                    }
                }
            }
            code.append(ch)
            i++
        }

        finishLine(stack.isEmpty())
        for ((_, l) in stack) errors.add(l)
        return errors
    }
}
