package com.knotyup.android.ui.screens

import com.knotyup.android.data.AppState
import com.knotyup.android.data.SessionState
import com.knotyup.android.net.GroupAd
import com.knotyup.android.ui.components.JoiningDialog
import com.knotyup.android.ui.components.KnotConfirm
import com.knotyup.android.ui.components.PinDialog
import com.knotyup.android.ui.components.QrDialog
import com.knotyup.android.ui.components.ShizukuGuideDialog
import androidx.compose.runtime.Composable

/**
 * App 级弹窗集合（确认/移出/PIN/二维码/Shizuku 引导/入群 Loading）。
 * 状态由组合根持有，本组件只做展示与回调分发。
 */
@Composable
internal fun AppDialogs(
    t: (String) -> String,
    session: SessionState,
    joining: Boolean,
    confirmLeave: Boolean,
    pendingKick: String?,
    pinDialogFor: GroupAd?,
    showQr: Boolean,
    showShizukuGuide: Boolean,
    onDismissConfirmLeave: () -> Unit,
    onConfirmLeaveOk: (String?) -> Unit,
    onPendingKick: (String?) -> Unit,
    onPinDialogFor: (GroupAd?) -> Unit,
    onShowQr: (Boolean) -> Unit,
    onShowShizukuGuide: (Boolean) -> Unit,
    startJoin: (String, String) -> Unit,
) {
    // 退出群二次确认
    if (confirmLeave) {
        val ownerLeave = AppState.isOwner()
        KnotConfirm(
            title = t(if (ownerLeave) "dissolve" else "confirm_leave_title"),
            message = t(if (ownerLeave) "transfer_hint" else "confirm_leave_msg"),
            okText = t("confirm_ok"),
            cancelText = t("confirm_cancel"),
            members = if (ownerLeave) {
                AppState.session.value.members.filter { it.id != AppState.device.id }
            } else emptyList(),
            onOk = onConfirmLeaveOk,
            onCancel = onDismissConfirmLeave,
        )
    }

    // 移出成员二次确认
    pendingKick?.let { kickId ->
        val m = AppState.session.value.members.firstOrNull { it.id == kickId }
        KnotConfirm(
            title = t("kick"),
            message = t("confirm_kick_msg").replace("{name}", m?.name ?: ""),
            okText = t("confirm_ok"),
            cancelText = t("confirm_cancel"),
            onOk = {
                AppState.server?.kick(kickId)
                onPendingKick(null)
            },
            onCancel = { onPendingKick(null) },
        )
    }

    // 加入群（PIN）弹窗：点击发现的群直接弹起
    pinDialogFor?.let { group ->
        PinDialog(
            t = t,
            group = group,
            onJoin = { pinValue ->
                onPinDialogFor(null)
                startJoin(group.addr, pinValue)
            },
            onDismiss = { onPinDialogFor(null) },
        )
    }

    // 群信息二维码弹窗
    if (showQr && session.joined) {
        QrDialog(
            t = t,
            addr = session.addr,
            pin = session.pin,
            onClose = { onShowQr(false) },
        )
    }

    // Shizuku 后台剪贴板引导弹窗（群页面模式徽章点击弹出）
    if (showShizukuGuide) {
        ShizukuGuideDialog(
            t = t,
            onDismiss = { onShowShizukuGuide(false) },
        )
    }

    // 加入群 Loading（不可关闭）
    if (joining) {
        JoiningDialog(t)
    }
}
