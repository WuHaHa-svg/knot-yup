package com.knotyup.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knotyup.android.data.AppState
import com.knotyup.android.net.DeviceInfo
import com.knotyup.android.net.SharedFile
import kotlinx.coroutines.launch

/** 文件 tab：一级=设备名文件夹，二级=该设备文件。数据来自 AppState.groupFiles。 */
@Composable
internal fun FileTab(t: (String) -> String, members: List<DeviceInfo>) {
    val groupFiles by AppState.groupFiles.collectAsState()
    var viewDevice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { AppState.shareFile(uri) }
                .onSuccess { AppState.toast(t("shared_ok"), 1) }
                .onFailure { AppState.toast(it.message ?: "error", 2) }
        }
    }

    fun nameOf(id: String): String {
        if (id == AppState.device.id) return AppState.device.name
        return members.firstOrNull { it.id == id }?.name ?: id.take(6)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (viewDevice != null) {
                Text(
                    "‹ ${t("back")}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { viewDevice = null },
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "+ ${t("share_file")}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { pickFile.launch(arrayOf("*/*")) },
            )
        }
        Spacer(Modifier.height(12.dp))

        if (viewDevice == null) {
            // 一级：设备文件夹——以成员表为准，群里有几个设备就有几个文件夹（空的也显示）
            val entries = members.map { it.id to groupFiles[it.id].orEmpty() }
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { Text(t("empty_file"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                LazyColumn(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    items(entries.toList()) { (devId, files) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { viewDevice = devId }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(com.knotyup.android.R.drawable.ic_devices), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(nameOf(devId), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("${files.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        } else {
            // 二级：该设备的文件
            val files = groupFiles[viewDevice].orEmpty()
            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { Text(t("empty_file"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                val isMine = viewDevice == AppState.device.id
                var confirmRemove by remember { mutableStateOf<SharedFile?>(null) }
                LazyColumn(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    items(files) { file ->
                        FileRow(
                            file = file,
                            isMine = isMine,
                            label = t(if (isMine) "remove" else "download"),
                            onDownload = {
                                scope.launch {
                                    AppState.toast(t("downloading"))
                                    runCatching { AppState.downloadSharedFile(viewDevice!!, file) }
                                        .onSuccess { AppState.toast(t("download_done"), 1) }
                                        .onFailure { AppState.toast(it.message ?: "error", 2) }
                                }
                            },
                            onRemove = { confirmRemove = file },
                        )
                    }
                }
                confirmRemove?.let { f ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmRemove = null },
                        title = { Text(t("remove")) },
                        text = { Text(t("confirm_remove").replace("{name}", f.name)) },
                        confirmButton = {
                            Text(t("remove"), color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    AppState.removeSharedFiles(listOf(f.fileId))
                                    confirmRemove = null
                                })
                        },
                        dismissButton = {
                            Text(t("confirm_cancel"), modifier = Modifier.clickable { confirmRemove = null })
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: SharedFile, isMine: Boolean, label: String, onDownload: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(com.knotyup.android.R.drawable.ic_file), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fmtSize(file.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontSize = 12.5.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = if (isMine) onRemove else onDownload),
        )
    }
}

private fun fmtSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1048576 -> String.format("%.1f KB", bytes / 1024f)
    bytes < 1073741824 -> String.format("%.1f MB", bytes / 1048576f)
    else -> String.format("%.2f GB", bytes / 1073741824f)
}
