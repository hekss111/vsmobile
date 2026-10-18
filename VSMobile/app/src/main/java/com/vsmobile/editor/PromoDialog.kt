package com.vsmobile.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val PROMO_MUTED = 0xFF9DA5B4

/**
 * Окно «Не потеряй нас»: показывается раз в [AppLinks.PROMO_INTERVAL_MS].
 * Текст чуть выше середины экрана, кнопки Telegram и GitHub — чуть ниже.
 */
@Composable
fun PromoDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB3000000))
        ) {
            val textTop = maxHeight * 0.28f
            val buttonsTop = maxHeight * 0.54f

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = textTop, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Не потеряй нас",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Наш Telegram и GitHub — следи за обновлениями VS Mobile",
                    color = Color(PROMO_MUTED),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = buttonsTop, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = { uriHandler.openUri(AppLinks.TELEGRAM) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Telegram") }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { uriHandler.openUri(AppLinks.GITHUB) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("GitHub") }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        }
    }
}
