package com.knotyup.android

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knotyup.android.clipboard.ClipboardCapability
import com.knotyup.android.data.AppState
import com.knotyup.android.net.DeviceInfo
import com.knotyup.android.net.Discovery
import com.knotyup.android.net.GroupAd
import com.knotyup.android.net.GroupEvent
import com.knotyup.android.net.textHash
import com.knotyup.android.data.HistoryItem
import com.knotyup.android.ui.KnotYupTheme
import com.knotyup.android.ui.Strings
import com.knotyup.android.ui.THEMES
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent?.takeIf { it.action == android.content.Intent.ACTION_SEND }
            ?.getStringExtra(android.content.Intent.EXTRA_TEXT)

        setContent {
            val context = LocalContext.current
            var themeId by remember { mutableStateOf(AppState.loadTheme(context) ?: "indigo") }
            var lang by remember {
                mutableStateOf(AppState.loadLang(context) ?: run {
                    val locale = resources.configuration.locales[0]
                    when {
                        locale.language != "zh" -> "en"
                        locale.country in listOf("TW", "HK", "MO") -> "zh-Hant"
                        else -> "zh-Hans"
                    }
                })
            }
            val t = { key: String -> Strings.t(lang, key) }

            KnotYupTheme(themeId) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(t, onTheme = { themeId = it; AppState.saveTheme(context, it) },
                        onLang = { lang = it; AppState.saveLang(context, it) }, sharedText)
                }
            }
        }
    }
}

@Composable
private fun App(
    t: (String) -> String,
    onTheme: (String) -> Unit,
    onLang: (String) -> Unit,
    sharedText: String?,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val session by AppState.session.collectAsState()
    val history by AppState.history.collectAsState()
    val toastMsg by AppState.toast.collectAsState()
    var discovered by remember { mutableStateOf<List<GroupAd>>(emptyList()) }
    var addr by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var joining by remember { mutableStateOf(false) }
    var sendText by remember { mutableStateOf(sharedText ?: "") }
    // 剪贴板指纹闸门[0]：最近一次同步的内容指纹（收/发都记录），防止「写入被当成本机复制」回环
    val clipGuard = remember { arrayOf("") }

    // 事件处理（含写本机剪贴板，需要 context）
    LaunchedEffect(Unit) {
        AppState.events.collect { event ->
            when (event) {
                is GroupEvent.Members -> AppState.applyEvent(event)
                is GroupEvent.Clipboard -> {
                    clipGuard[0] = event.payload.hash
                    AppState.applyEvent(event)
                    ClipboardCapability.setText(context, event.payload.text)
                }
                is GroupEvent.Kicked -> {
                    AppState.reset()
                    AppState.toast(t("kicked"))
                }
                GroupEvent.Disconnected -> {
                    AppState.reset()
                    AppState.toast(t("disconnected"))
                }
                is GroupEvent.Error -> AppState.toast(event.message)
            }
        }
    }

    // 发现监听（未入群时）
    LaunchedEffect(session.joined) {
        if (!session.joined) Discovery.listen().collect { discovered = it }
    }

    // 前台剪贴板监听：本机复制 → 发送到群
    LaunchedEffect(session.joined) {
        if (session.joined) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val listener = ClipboardManager.OnPrimaryClipChangedListener {
                val text = ClipboardCapability.getText(context) ?: return@OnPrimaryClipChangedListener
                val hash = textHash(text)
                // 跳过刚写入的远端同步内容（指纹相同）
                if (text.isNotEmpty() && hash != clipGuard[0]) {
                    clipGuard[0] = hash
                    if (AppState.client.sendClipboard(text)) AppState.addLocalHistory(text)
                }
            }
            cm.addPrimaryClipChangedListener(listener)
            try {
                while (true) delay(60_000) // 保持监听直到离开组合/入群状态变化
            } finally {
                cm.removePrimaryClipChangedListener(listener)
            }
        }
    }

    // toast 自动消失
    LaunchedEffect(toastMsg) {
        if (toastMsg != null) {
            delay(2200)
            AppState.clearToast()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Header(onTheme, onLang)
            if (session.joined) {
                GroupBody(t, session.members, history, sendText,
                    onSendText = { sendText = it },
                    onSend = {
                        if (sendText.isNotBlank() && AppState.client.sendClipboard(sendText)) {
                            AppState.addLocalHistory(sendText)
                            AppState.toast(t("sent"))
                            sendText = ""
                        }
                    },
                    onLeave = {
                        AppState.reset()
                        AppState.toast(t("left"))
                    })
            } else {
                JoinBody(t, discovered, addr, pin, joining,
                    onAddr = { addr = it }, onPin = { pin = it },
                    onPick = { addr = it.addr },
                    onJoin = {
                        if (addr.isBlank() || pin.isBlank()) {
                            AppState.toast(t("fill_addr_pin"))
                        } else {
                            joining = true
                            scope.launch {
                                AppState.client.join(addr, pin, AppState.device).collect { result ->
                                    result.fold({ event ->
                                        AppState.emit(event)
                                        when (event) {
                                            is GroupEvent.Members -> joining = false
                                            is GroupEvent.Kicked, GroupEvent.Disconnected -> joining = false
                                            else -> {}
                                        }
                                    }, { e ->
                                        AppState.toast(
                                            when (e.message) {
                                                "WRONG_PIN" -> t("wrong_pin")
                                                else -> "${t("join_failed")}\n${e.message ?: ""}"
                                            }
                                        )
                                        joining = false
                                    })
                                }
                            }
                        }
                    })
            }
        }

        // Toast
        toastMsg?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .background(Color(0xE61D2129), RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(msg, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

// —— UI 组件 ————————————————————————————

@Composable
private fun Header(onTheme: (String) -> Unit, onLang: (String) -> Unit) {
    var themeOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("KnotYup", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.weight(1f))
        if (themeOpen) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                THEMES.forEach { (id, color) ->
                    Box(
                        Modifier
                            .padding(3.dp)
                            .size(20.dp)
                            .background(color, androidx.compose.foundation.shape.CircleShape)
                            .clickable { onTheme(id); themeOpen = false },
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
        }
        TextButton(onClick = { themeOpen = !themeOpen }, contentPadding = PaddingValues(6.dp)) {
            Text("🎨", fontSize = 14.sp)
        }
        TextButton(onClick = { onLang(Strings.nextLang()) }, contentPadding = PaddingValues(6.dp)) {
            Text(Strings.langLabels[Strings.currentLang] ?: "简", fontSize = 13.sp)
        }
    }
}

@Composable
private fun JoinBody(
    t: (String) -> String,
    discovered: List<GroupAd>,
    addr: String, pin: String, joining: Boolean,
    onAddr: (String) -> Unit, onPin: (String) -> Unit,
    onPick: (GroupAd) -> Unit, onJoin: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(t("tagline"), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(t("tagline_sub"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(t("join_group"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(t("join_desc"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))

                Text(t("discovered"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (discovered.isEmpty()) {
                    Text(
                        t("no_discovered"), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    discovered.forEach { g ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onPick(g) }
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(6.dp).background(
                                    MaterialTheme.colorScheme.primary,
                                    androidx.compose.foundation.shape.CircleShape,
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                g.name, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(g.addr, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = addr, onValueChange = onAddr, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(t("addr_hint"), fontSize = 13.sp) }, singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin, onValueChange = onPin, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(t("pin_hint"), fontSize = 13.sp) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onJoin, enabled = !joining,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                ) { Text(t("btn_join")) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun GroupBody(
    t: (String) -> String,
    members: List<DeviceInfo>,
    history: List<HistoryItem>,
    sendText: String,
    onSendText: (String) -> Unit,
    onSend: () -> Unit,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    val shizuku = remember { ClipboardCapability.isShizukuActive() && ClipboardCapability.isShizukuPermissionGranted() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                if (shizuku) t("shizuku_mode_hint") else t("fg_mode_hint"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
        item {
            Text(t("devices"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            members.forEachIndexed { i, m ->
                val self = m.id == AppState.device.id
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(32.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            m.name.take(1).uppercase(), color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            if (self) {
                                Spacer(Modifier.width(6.dp))
                                Text(t("you"), fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            if (i == 0) {
                                Spacer(Modifier.width(6.dp))
                                Text("👑 ${t("owner")}", fontSize = 10.sp, color = Color(0xFFB45309))
                            }
                        }
                        Text(m.platform, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        item {
            Text(t("clipboard"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(t("click_hint"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
        }
        if (history.isEmpty()) {
            item {
                Text(
                    "${t("empty_1")}\n${t("empty_2")}", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
        } else {
            items(history) { item ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clickable {
                            ClipboardCapability.setText(context, item.text)
                            AppState.toast(t("copied"))
                        }
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    val name = members.firstOrNull { it.id == item.from }?.name
                        ?: if (item.from == AppState.device.id) AppState.device.name else item.from.take(6)
                    Text(
                        name, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(item.text, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = sendText, onValueChange = onSendText,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(t("send_hint"), fontSize = 13.sp) },
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSend, shape = RoundedCornerShape(10.dp), modifier = Modifier.height(52.dp)) {
                    Text(t("send"))
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onLeave,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) { Text(t("leave")) }
        }
    }
}
