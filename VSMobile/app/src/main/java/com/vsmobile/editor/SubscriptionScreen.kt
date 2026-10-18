package com.vsmobile.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsmobile.editor.net.AccountRepository
import com.vsmobile.editor.net.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val MUTED = 0xFF9DA5B4

private data class PlanInfo(
    val id: String,
    val title: String,
    val priceRub: Int,
    val dailyRequests: Int,
    val perks: List<String>
)

private val plans = listOf(
    PlanInfo("pro", "Pro", 150, 30, listOf("Встроенный ИИ-помощник", "30 запросов в день")),
    PlanInfo("max", "Max", 450, 100, listOf("Встроенный ИИ-помощник", "100 запросов в день", "Приоритет на новые возможности"))
)

/** Ссылка на оплату на сайте с параметрами тарифа и почты. */
private fun buildPayUrl(base: String, plan: String, email: String?): String {
    val b = android.net.Uri.parse(base).buildUpon().appendQueryParameter("plan", plan)
    if (!email.isNullOrBlank()) b.appendQueryParameter("email", email)
    return b.build().toString()
}

@Composable
private fun PlanCard(
    plan: PlanInfo,
    isCurrent: Boolean,
    payEnabled: Boolean,
    payLabel: String,
    onPay: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppTheme.surface),
        border = if (isCurrent) androidx.compose.foundation.BorderStroke(2.dp, AppTheme.accent) else null
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(plan.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${plan.priceRub} ₽/мес", fontSize = 18.sp, color = AppTheme.accent, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            plan.perks.forEach { Text("• $it", color = Color(MUTED), fontSize = 13.sp) }
            Spacer(Modifier.height(12.dp))
            if (isCurrent) {
                Text("Ваш текущий тариф", color = AppTheme.accent, fontSize = 13.sp)
            } else {
                Button(onClick = onPay, enabled = payEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text(payLabel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    account: AccountRepository,
    onBack: () -> Unit,
    onNeedLogin: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var subscription by remember { mutableStateOf<JSONObject?>(null) }
    var usage by remember { mutableStateOf<JSONObject?>(null) }

    fun refresh() {
        if (!account.isLoggedIn) {
            loading = false
            return
        }
        loading = true
        error = null
        scope.launch {
            try {
                val (sub, use) = withContext(Dispatchers.IO) {
                    account.fetchSubscription() to runCatching { account.fetchUsage() }.getOrNull()
                }
                subscription = sub
                usage = use
            } catch (e: ApiException) {
                error = e.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // После оплаты на сайте пользователь возвращается в приложение — обновляем статус автоматически.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var firstResume = true // первый ON_RESUME уже покрыт LaunchedEffect выше
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (firstResume) firstResume = false else refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Подписка", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!account.isLoggedIn) {
                Text(
                    "Тарифы встроенного ИИ-помощника. Чтобы оплатить, сначала войдите в аккаунт.",
                    color = Color(MUTED)
                )
                plans.forEach { p ->
                    PlanCard(p, isCurrent = false, payEnabled = true, payLabel = "Войти и оплатить", onPay = onNeedLogin)
                }
                return@Scaffold
            }

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            error?.let {
                Text(it, color = Color(0xFFF48771))
                OutlinedButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Повторить")
                }
            }

            subscription?.let { sub ->
                val plan = sub.optString("plan", "free")
                val active = sub.optBoolean("active", false)
                val limit = sub.optInt("daily_limit", 0)
                val purchaseUrl = sub.optString("purchase_url", "").takeIf { it.isNotBlank() }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AppTheme.surface)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            when (plan) {
                                "pro" -> "Тариф: Pro"
                                "max" -> "Тариф: Max"
                                else -> "Тариф: Free"
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (active) "Подписка активна" else "Встроенный ИИ недоступен без подписки или своего API-ключа",
                            color = Color(MUTED),
                            fontSize = 13.sp
                        )
                        if (active) {
                            Spacer(Modifier.height(12.dp))
                            Text("Лимит запросов в день: $limit", fontSize = 14.sp)
                            usage?.let { u ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Использовано сегодня: ${u.optInt("used", 0)} / ${u.optInt("limit", limit)}",
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                Text("Тарифы", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                plans.forEach { p ->
                    PlanCard(
                        plan = p,
                        isCurrent = active && plan == p.id,
                        payEnabled = purchaseUrl != null,
                        payLabel = if (active) "Перейти на ${p.title} · Оплатить" else "Оплатить ${p.priceRub} ₽",
                        onPay = {
                            purchaseUrl?.let { uriHandler.openUri(buildPayUrl(it, p.id, account.userEmail)) }
                        }
                    )
                }
                if (purchaseUrl == null) {
                    Text(
                        "Оплата пока не настроена на сервере.",
                        color = Color(MUTED),
                        fontSize = 12.sp
                    )
                } else {
                    Text(
                        "Оплата проходит на сайте. После оплаты вернитесь в приложение — подписка подтянется автоматически.",
                        color = Color(MUTED),
                        fontSize = 12.sp
                    )
                    OutlinedButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Обновить статус подписки")
                    }
                }

                Text(
                    "Если в настройках указан собственный API-ключ, он используется вместо подписки — лимиты и подписка на него не влияют.",
                    color = Color(MUTED),
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    account.logout()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Выйти из аккаунта") }
        }
    }
}
