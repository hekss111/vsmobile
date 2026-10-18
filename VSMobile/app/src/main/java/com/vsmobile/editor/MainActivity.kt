package com.vsmobile.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.TextFieldValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        AppTheme.load(this)
        setContent {
            VSMobileTheme {
                VSMobileApp()
            }
        }
    }
}

@Composable
fun VSMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppTheme.colorScheme(), content = content)
}

data class OpenTab(
    val file: DocumentFile,
    var content: TextFieldValue,
    var isDirty: Boolean = false
)

/** Корень UI: выбирает экран — редактор открытого проекта, вход, подписка, настройки или список проектов. */
@Composable
fun VSMobileApp() {
    val vm: EditorViewModel = viewModel()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAuth by rememberSaveable { mutableStateOf(false) }
    var showSubscription by rememberSaveable { mutableStateOf(false) }
    var showPlugins by rememberSaveable { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // После onStop процесс может быть убит в любой момент — пишем синхронно.
            if (event == Lifecycle.Event.ON_STOP) vm.saveAllTabsNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = vm.currentProject != null) { vm.closeProject() }

    BackHandler(enabled = showAuth || showSubscription || showSettings || showPlugins) {
        when {
            showAuth -> showAuth = false
            showSubscription -> showSubscription = false
            showPlugins -> showPlugins = false
            showSettings -> showSettings = false
        }
    }

    var showPromo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(AppLinks.PROMO_INTERVAL_MS)
            showPromo = true
        }
    }
    if (showPromo) PromoDialog(onDismiss = { showPromo = false })

    val project = vm.currentProject
    if (project != null) {
        EditorScreen(vm = vm, project = project, onOpenSettings = { showSettings = true })
        return
    }

    when {
        showAuth -> AuthScreen(
            account = vm.account,
            onBack = { showAuth = false },
            onLoggedIn = { showAuth = false }
        )
        showSubscription -> SubscriptionScreen(
            account = vm.account,
            onBack = { showSubscription = false },
            onNeedLogin = {
                showSubscription = false
                showAuth = true
            }
        )
        showPlugins -> PluginsScreen(onBack = { showPlugins = false })
        showSettings -> SettingsScreen(
            repo = vm.repo,
            account = vm.account,
            onBack = { showSettings = false },
            // Список проектов при возврате из настроек строится заново, отдельное обновление не нужно.
            onRecentCleared = {},
            onOpenAuth = { showAuth = true },
            onOpenSubscription = { showSubscription = true }
        )
        else -> ProjectListScreen(
            vm = vm,
            onOpenSettings = { showSettings = true },
            onOpenPlugins = { showPlugins = true }
        )
    }
}
