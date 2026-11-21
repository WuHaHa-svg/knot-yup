package com.knotyup.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knotyup.android.R
import com.knotyup.android.data.AppState
import com.knotyup.android.net.DeviceInfo
import com.knotyup.android.net.SharedFile
import com.knotyup.android.net.TaskStatus
import com.knotyup.android.net.TransferTask
import com.knotyup.android.ui.Motion
import com.knotyup.android.ui.components.KnotConfirm
import kotlinx.coroutines.launch

/**
 * 文件 tab：一级=设备名文件夹，二级=该设备文件。数据来自 AppState.groupFiles。
 * [seenFileItems] 由 pager 之外的 GroupBody 持有：切页 dispose 后重进不重播挂载动画，
 * 与剪贴板 seenClipItems 同理——只有真正新条目才播。
 */
@Composable
internal fun FileTab(t: (String) -> String, members: List<DeviceInfo>, seenFileItems: MutableSet<String>) {
    val groupFiles by AppState.groupFiles.collectAsState()
    val transferTasks by AppState.transferTasks.collectAsState()
    var viewDevice by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<SharedFile?>(null) }
    val scope = rememberCoroutineScope()

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { AppState.shareFile(uri) }
                .onSuccess { AppState.toast(t("shared_ok"), 1) }
                .onFailure { AppState.toast(it.message ?: "error", 2) }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 96.dp),
    ) {
        if (viewDevice == null) {
            // 一级标题：大标题 + 小副标题（与其他 tab 一致）
            Text(t("files"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(t("files_sub"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            // 二级：返回按钮（仅图标 + 文字，样式与大标题一致）+ 下方小副标题
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { viewDevice = null }
                    .padding(vertical = 2.dp),
            ) {
                Image(
                    painterResource(R.drawable.ic_back), null, Modifier.size(16.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                )
                Spacer(Modifier.width(4.dp))
                Text(t("back"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Text(t("back_sub"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))

        if (viewDevice == null) {
            // 一级：设备文件夹——以成员表为准，每行平台文件夹图标
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(members, key = { it.id }) { m ->
                    key(m.id) {
                        // 动画只播一次：已在 seenFileItems 中的条目切页回来直接 visible
                        var shown by remember { mutableStateOf(m.id in seenFileItems) }
                        LaunchedEffect(Unit) { seenFileItems.add(m.id); shown = true }
                        AnimatedVisibility(
                            visible = shown,
                            enter = fadeIn(tween(220)) +
                                slideInVertically(tween(240, easing = Motion.Easing)) { -it / 3 } +
                                expandVertically(tween(200)),
                        ) {
                            DeviceFolderRow(
                                member = m,
                                count = groupFiles[m.id]?.size ?: 0,
                                onClick = { viewDevice = m.id },
                            )
                        }
                    }
                }
                // 传输中心段：与群信息 tab 同款（标题 + 副标题 + 内容），与文件夹列表一起滚动
                item(key = "transfer_center") {
                    Spacer(Modifier.height(10.dp))
                    Text(t("tasks"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(t("tasks_hint"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    if (transferTasks.isEmpty()) {
                        Text(
                            t("tasks_empty"), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        )
                    } else {
                        val ongoing = transferTasks.filter { it.status == TaskStatus.QUEUING || it.status == TaskStatus.TRANSFERRING }
                        val finished = transferTasks.filter { it.status == TaskStatus.DONE || it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED }
                        (ongoing + finished).forEach { task -> TransferTaskRow(task, t) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        } else {
            // 二级：该设备的文件（空态也放进列表，共享按钮固定在底部）
            val files = groupFiles[viewDevice].orEmpty()
            val isMine = viewDevice == AppState.device.id
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (files.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                            Text(
                                t("empty_file"), fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                items(files, key = { it.fileId }) { file ->
                    key(file.fileId) {
                        // 动画只播一次：已在 seenFileItems 中的条目切页回来直接 visible
                        var shown by remember { mutableStateOf(file.fileId in seenFileItems) }
                        LaunchedEffect(Unit) { seenFileItems.add(file.fileId); shown = true }
                        AnimatedVisibility(
                            visible = shown,
                            enter = fadeIn(tween(220)) +
                                slideInVertically(tween(240, easing = Motion.Easing)) { -it / 3 } +
                                expandVertically(tween(200)),
                        ) {
                            FileRow(
                                file = file,
                                actionText = t(if (isMine) "remove" else "download"),
                                actionColor = if (isMine) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                                onAction = {
                                    if (isMine) {
                                        confirmRemove = file
                                    } else {
                                        scope.launch {
                                            AppState.toast(t("downloading"))
                                            runCatching { AppState.downloadSharedFile(viewDevice!!, file) }
                                                .onSuccess { AppState.notifyDownloaded(file.name) }
                                                .onFailure { AppState.toast(it.message ?: "error", 2) }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // 底部共享按钮：位置对齐群信息 tab 的退出群 / 剪贴板 tab 的输入框；描边模式
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { pickFile.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            Image(
                painterResource(R.drawable.ic_plus), null, Modifier.size(14.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(6.dp))
            Text(t("share_file"), fontSize = 13.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }

    // 删除确认：统一弹窗组件（不可点空白关闭）
    confirmRemove?.let { f ->
        KnotConfirm(
            title = t("remove"),
            message = t("confirm_remove").replace("{name}", f.name),
            okText = t("remove"),
            cancelText = t("confirm_cancel"),
            onOk = {
                AppState.removeSharedFiles(listOf(f.fileId))
                confirmRemove = null
            },
            onCancel = { confirmRemove = null },
        )
    }
}

/** 一级：设备文件夹行（文件夹图标 chip + 名称 + 数量），卡片样式对齐成员行。 */
@Composable
private fun DeviceFolderRow(member: DeviceInfo, count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_folder), null, Modifier.size(17.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            member.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
        // 数量徽章：与桌面一致的主题色 9% 混白底 + 正圆
        Box(
            Modifier.size(18.dp)
                .clip(CircleShape)
                .background(lerp(Color.White, MaterialTheme.colorScheme.primary, 0.09f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$count", fontSize = 10.sp, lineHeight = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 传输中心任务行：方向图标 + 文件名 + 对方 + 状态/进度条（只读）。 */
@Composable
private fun TransferTaskRow(task: TransferTask, t: (String) -> String) {
    val statusText = when (task.status) {
        TaskStatus.QUEUING -> t("task_queuing")
        TaskStatus.TRANSFERRING -> t("task_transferring")
        TaskStatus.DONE -> t("task_done")
        TaskStatus.FAILED -> t("task_failed")
        TaskStatus.CANCELLED -> t("task_cancelled")
    }
    // 方向由固化的设备名快照表达：seederName 发送给 downloaderName（"发送给"主色加粗）
    val seederName = task.seederName
    val downloaderName = task.downloaderName
    val peerText = buildAnnotatedString {
        append(seederName)
        append(" ")
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) {
            append(t("task_sent_to"))
        }
        append(" ")
        append(downloaderName)
    }
    val pct = if (task.status == TaskStatus.DONE) 100 else task.progress.coerceIn(0, 100)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_transfer),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(task.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                peerText, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                Modifier.fillMaxWidth().height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(Modifier.fillMaxWidth(pct / 100f).height(3.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (task.status == TaskStatus.FAILED && task.error.isNotBlank()) "$statusText · ${task.error}"
            else if (task.status == TaskStatus.QUEUING) statusText
            else "$statusText · $pct%",
            fontSize = 11.sp,
            // 状态配色：排队灰 / 传输中主色 / 已完成绿 / 失败红
            color = when (task.status) {
                TaskStatus.QUEUING -> MaterialTheme.colorScheme.onSurfaceVariant
                TaskStatus.TRANSFERRING -> MaterialTheme.colorScheme.primary
                TaskStatus.DONE -> Color(0xFF34A97B)
                TaskStatus.FAILED -> MaterialTheme.colorScheme.error
                TaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
        )
    }
}

/** 二级：文件行（文件图标 chip + 名称/大小 + 动作按钮），卡片样式对齐剪贴板条目。 */
@Composable
private fun FileRow(file: SharedFile, actionText: String, actionColor: Color, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (iconRes, tintColor) = fileIcon(file.name)
        val dark = androidx.compose.foundation.isSystemInDarkTheme()
        Box(
            Modifier.size(32.dp)
                .background(tintColor.copy(alpha = if (dark) 0.22f else 0.14f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(iconRes), null, Modifier.size(17.dp),
                colorFilter = ColorFilter.tint(tintColor),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(fmtSize(file.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(
            onClick = onAction,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        ) { Text(actionText, fontSize = 11.sp, color = actionColor) }
    }
}

private fun fmtSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1048576 -> String.format("%.1f KB", bytes / 1024f)
    bytes < 1073741824 -> String.format("%.1f MB", bytes / 1048576f)
    else -> String.format("%.2f GB", bytes / 1073741824f)
}

/** 按扩展名映射文件类型图标与主题色（双端图标集 + 配色保持一致）。 */
private fun fileIcon(name: String): Pair<Int, Color> {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "heic", "ico" ->
            R.drawable.ic_img to Color(0xFFEC4899) // 图片 · 粉
        "doc", "docx", "txt", "md", "pdf", "rtf", "pages" ->
            R.drawable.ic_doc to Color(0xFF3B82F6) // 文档 · 蓝
        "xls", "xlsx", "csv", "numbers" ->
            R.drawable.ic_sheet to Color(0xFF16A34A) // 表格 · 绿
        "mp4", "mov", "avi", "mkv", "webm", "flv" ->
            R.drawable.ic_video to Color(0xFF8B5CF6) // 视频 · 紫
        "mp3", "wav", "flac", "aac", "ogg", "m4a" ->
            R.drawable.ic_audio to Color(0xFFEA580C) // 音频 · 橙
        "zip", "rar", "7z", "tar", "gz", "bz2" ->
            R.drawable.ic_archive to Color(0xFFB45309) // 压缩包 · 琥珀
        "apk", "aab" ->
            R.drawable.ic_apk to Color(0xFF34A853) // 安卓包 · 安卓绿
        "exe", "msi" ->
            R.drawable.ic_exe to Color(0xFF0078D4) // Windows 可执行 · 视窗蓝
        "dmg", "pkg", "app" ->
            R.drawable.ic_dmg to Color(0xFF64748B) // macOS 安装 · 石板灰
        else -> R.drawable.ic_file to Color(0xFF6B7280)
    }
}
