package com.vsmobile.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CodeEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    language: String,
    modifier: Modifier = Modifier
) {
    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val fontSize = 14.sp
    val lineHeight = 20.sp
    val lineHeightDp = with(LocalDensity.current) { lineHeight.toDp() }

    val context = LocalContext.current
    remember { EditorSettings.load(context) }
    val autoCloseActive = EditorSettings.isAutoCloseActiveFor(language)

    val highlighted = remember(value.text, language) {
        SyntaxHighlighter.highlight(value.text, language)
    }

    val errorLines: Set<Int> = remember(value.text, language) {
        SyntaxHighlighter.findErrorLines(value.text, language)
    }

    Row(
        modifier = modifier
            .background(SyntaxHighlighter.background)
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .background(SyntaxHighlighter.gutterBg)
                .verticalScroll(verticalScroll)
                .padding(vertical = 12.dp, horizontal = 8.dp)
                .widthIn(min = 40.dp)
        ) {
            for (i in 1..lineCount) {
                Text(
                    text = i.toString(),
                    color = SyntaxHighlighter.gutterText,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 24.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(verticalScroll)
                .horizontalScroll(horizontalScroll)
                .padding(start = 18.dp, top = 12.dp, bottom = 12.dp, end = 10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = { newValue ->
                    onValueChange(
                        if (autoCloseActive) handleAutoClose(value, newValue, language) else newValue
                    )
                },
                textStyle = TextStyle(
                    color = Color.Transparent,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = SolidColor(Color.White),
                decorationBox = { innerTextField ->
                    Box {
                        Column {
                            for (idx in 0 until lineCount) {
                                Box(
                                    Modifier
                                        .widthIn(min = 3000.dp)
                                        .height(lineHeightDp)
                                        .background(
                                            if (errorLines.contains(idx))
                                                SyntaxHighlighter.errorBg
                                            else Color.Transparent
                                        )
                                )
                            }
                        }

                        Text(
                            text = highlighted,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false
                        )

                        innerTextField()
                    }
                }
            )
        }
    }
}

private fun handleAutoClose(
    old: TextFieldValue,
    new: TextFieldValue,
    language: String
): TextFieldValue {
    val oldText = old.text
    val newText = new.text

    if (newText.length != oldText.length + 1) return new

    val cursor = new.selection.start
    if (cursor <= 0 || cursor > newText.length) return new

    val inserted = newText[cursor - 1]

    // Печатаем закрывающий символ, а он уже стоит справа — просто сдвигаем курсор, не дублируем.
    if (inserted in ")]}'\"" && cursor - 1 < oldText.length && oldText[cursor - 1] == inserted) {
        return TextFieldValue(text = oldText, selection = TextRange(cursor))
    }

    val pairs = mapOf(
        '(' to ')',
        '[' to ']',
        '{' to '}',
        '\'' to '\'',
        '"' to '"'
    )
    val closing = pairs[inserted] ?: return new

    // Апостроф после буквы (it's, don't) — это не начало строки, пару не ставим.
    if (inserted == '\'' && cursor >= 2 && newText[cursor - 2].isLetterOrDigit()) return new

    if (cursor < newText.length && newText[cursor].isLetterOrDigit()) return new

    val resultText = newText.substring(0, cursor) + closing + newText.substring(cursor)
    return TextFieldValue(
        text = resultText,
        selection = TextRange(cursor)
    )
}

@Composable
fun QuickSymbolsBar(onInsert: (String) -> Unit) {
    val symbols = listOf(
        "{", "}", "(", ")", "[", "]", ";", ":", "\"", "=", "<", ">", "/", "_", "-", "+", "Tab"
    )
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.surface)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        items(symbols.size) { idx ->
            val s = symbols[idx]
            OutlinedButton(
                onClick = { onInsert(if (s == "Tab") "    " else s) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFD4D4D4)
                )
            ) {
                Text(s, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
    }
}