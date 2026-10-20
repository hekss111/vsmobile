package com.vsmobile.editor.plugins

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.vsmobile.editor.AppTheme
import com.vsmobile.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ActivePanel(val pluginId: String, val panelId: String)

private class LoadedPanel(val installed: InstalledPlugin, val panel: PluginPanel)

private fun hex(color: Color): String = "#" + AppTheme.hexOf(color)

fun panelTokens(): PanelTokens = PanelTokens(
    background = hex(AppTheme.background),
    surface = hex(AppTheme.surface),
    divider = hex(AppTheme.divider),
    accent = hex(AppTheme.accent),
    text = "#D4D4D4",
    muted = "#9DA5B4",
    danger = "#F48771"
)

private class PanelBridge(private val api: HostApi) {
    @JavascriptInterface
    fun call(method: String, args: String): String = api.call(method, args)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginPanelHost(vm: EditorViewModel) {
    val active = vm.activePanel ?: return
    val loaded by produceState<LoadedPanel?>(null, active) {
        value = withContext(Dispatchers.IO) {
            val item = vm.pluginStore.list()
                .firstOrNull { it.plugin.id == active.pluginId && it.enabled && Permission.UI in it.granted }
            val panel = item?.plugin?.panels?.firstOrNull { it.id == active.panelId }
            if (item != null && panel != null) LoadedPanel(item, panel) else null
        }
    }
    val current = loaded ?: return
    PluginPanelSheet(vm, current.installed, current.panel, panelTokens()) { vm.activePanel = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PluginPanelSheet(
    vm: EditorViewModel,
    installed: InstalledPlugin,
    panel: PluginPanel,
    tokens: PanelTokens,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val pluginId = installed.plugin.id
    val network = Permission.NETWORK in installed.granted
    val shim = remember { context.assets.open("vsm_shim.js").bufferedReader().use { it.readText() } }
    val api = remember(pluginId, panel.id, installed.granted) {
        HostApi(
            installed.granted,
            AndroidHostEnv(vm, pluginId),
            PluginStorage(vm.pluginStore.storageFile(pluginId)),
            object : BlockingHooks {
                override fun <T> blocking(block: () -> T): T = block()
            },
            panel.id
        )
    }
    val document = remember(panel, tokens, network, shim) { PanelTheme.document(panel, tokens, network, shim) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val sinkKey = "$pluginId/${panel.id}"

    DisposableEffect(web) {
        val view = web
        if (view != null) {
            vm.panelSinks[sinkKey] = { json ->
                view.evaluateJavascript(
                    "window.__vsmPanelMessage&&window.__vsmPanelMessage(${JSONObject.quote(json)})",
                    null
                )
            }
        }
        onDispose { vm.panelSinks.remove(sinkKey) }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AppTheme.surface
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(panel.title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(AndroidColor.parseColor(tokens.background))
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.domStorageEnabled = false
                        settings.setSupportMultipleWindows(false)
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                        }
                        addJavascriptInterface(PanelBridge(api), "VsmHost")
                        loadDataWithBaseURL(
                            "https://plugin-$pluginId.vsm.invalid/",
                            document,
                            "text/html",
                            "utf-8",
                            null
                        )
                        web = this
                    }
                },
                onRelease = { it.destroy() }
            )
        }
    }
}
