package com.knotyup.android.net

import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 与 knot-core/src/protocol.rs 对应的消息模型（v2，JSON + 加密信封）。
 * 常量与消息结构必须与 Rust 侧逐字段一致（docs/ws-design.md §3）。
 */

/** v2 协议常量（与 knot-core protocol.rs 一致） */
object Proto {
    const val HEARTBEAT_MS = 30_000L
    const val PONG_TIMEOUT_MS = 3_000L
    const val WATCHDOG_MS = 35_000L
    const val TRANSFER_TIMEOUT_MS = 5_000L
    const val DISSOLVE_EXIT_MS = 800L
    /** 文件分块大小 64KiB，与 knot-core FILE_CHUNK_SIZE 一致 */
    const val FILE_CHUNK_SIZE = 65_536
}

data class DeviceInfo(
    val id: String,
    val name: String,
    val platform: String = "android",
    /** 本机 standby 服务地址（ip:port）；群主/未预留为 null */
    val addr: String? = null,
    /** 文件共享 P2P 供种服务地址（ip:port），与 ws/standby 端口独立 */
    val fileAddr: String? = null,
    /** 触控板专用 TCP 地址（ip:port），桌面端常驻监听；安卓端为 null */
    val touchpadAddr: String? = null,
    /** 成员身份：owner | member。服务端权威赋值；join 上报省略 */
    val role: String? = null,
    /** 该成员共享的文件列表（文件夹随成员走）。join 时携带，随成员表同步 */
    val fileList: List<SharedFile> = emptyList(),
) {
    val isOwner: Boolean get() = role == "owner"

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("platform", platform)
        addr?.let { put("addr", it) }
        fileAddr?.let { put("file_addr", it) }
        role?.let { put("role", it) }
        put("file_list", org.json.JSONArray(fileList.map { it.toJson() }))
    }

    companion object {
        fun fromJson(o: JSONObject) = DeviceInfo(
            o.getString("id"), o.getString("name"), o.optString("platform", "android"),
            if (o.has("addr")) o.getString("addr") else null,
            if (o.has("file_addr")) o.getString("file_addr") else null,
            if (o.has("touchpad_addr")) o.getString("touchpad_addr") else null,
            if (o.has("role")) o.getString("role") else null,
            if (o.has("file_list")) SharedFile.listFromJson(o.getJSONArray("file_list")) else emptyList(),
        )
    }
}

data class ClipboardPayload(val kind: String, val text: String, val hash: String) {
    fun toJson() = JSONObject().apply {
        put("kind", kind); put("text", text); put("hash", hash)
    }
}

/** 文件共享条目（网络载荷；本地路径只在 FileIndex 里，绝不上报）。 */
data class SharedFile(
    val fileId: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val mime: String,
    val addedAt: Long,
) {
    fun toJson() = JSONObject().apply {
        put("file_id", fileId); put("name", name); put("size", size)
        put("sha256", sha256); put("mime", mime); put("added_at", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = SharedFile(
            o.getString("file_id"), o.getString("name"), o.getLong("size"),
            o.getString("sha256"), o.getString("mime"), o.getLong("added_at"),
        )

        fun listFromJson(arr: org.json.JSONArray) =
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
    }
}

/** 传输任务状态（与 knot-core TaskStatus 逐字段一致，snake_case 线上值）。 */
enum class TaskStatus(val wire: String) {
    QUEUING("queuing"), TRANSFERRING("transferring"), DONE("done"), FAILED("failed"), CANCELLED("cancelled");
    companion object { fun fromWire(s: String) = values().firstOrNull { it.wire == s } ?: QUEUING }
}

/** 一条传输任务：一次 P2P 传输 = 一条任务，含下载方与供种方两个视角。 */
data class TransferTask(
    val taskId: String,
    val fileId: String,
    val name: String,
    val size: Long,
    val mime: String,
    val seederId: String,
    val seederName: String,
    val downloaderId: String,
    val downloaderName: String,
    val status: TaskStatus,
    val progress: Int,
    val transferred: Long,
    val error: String,
    val cancellerId: String,
    val cancellerName: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson() = JSONObject().apply {
        put("task_id", taskId); put("file_id", fileId); put("name", name)
        put("size", size); put("mime", mime)
        put("seeder_id", seederId); put("seeder_name", seederName)
        put("downloader_id", downloaderId); put("downloader_name", downloaderName)
        put("status", status.wire); put("progress", progress); put("transferred", transferred)
        put("error", error); put("canceller_id", cancellerId); put("canceller_name", cancellerName)
        put("created_at", createdAt); put("updated_at", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = TransferTask(
            o.getString("task_id"), o.getString("file_id"), o.getString("name"),
            o.getLong("size"), o.getString("mime"),
            o.getString("seeder_id"), o.getString("seeder_name"),
            o.getString("downloader_id"), o.getString("downloader_name"),
            TaskStatus.fromWire(o.getString("status")),
            o.optInt("progress"), o.optLong("transferred"), o.optString("error"),
            o.getString("canceller_id"), o.getString("canceller_name"),
            o.optLong("created_at"), o.optLong("updated_at"),
        )

        fun listFromJson(arr: org.json.JSONArray) =
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
    }
}

sealed class Msg {
    data class Join(val pin: String, val device: DeviceInfo) : Msg()
    data class JoinResult(val ok: Boolean, val reason: String?, val members: List<DeviceInfo>) : Msg()
    data class Members(val members: List<DeviceInfo>) : Msg()
    data class Ping(val device: DeviceInfo, val seq: Long, val tsMs: Long) : Msg()
    /** 心跳回执：成员表快照即状态同步（群主身份看 role 字段） */
    data class Pong(val members: List<DeviceInfo>, val seq: Long, val tsMs: Long) : Msg()
    /** 剪贴板：from = 发送者 device.id，接收端 from == 自己 id 时忽略（回声去重） */
    data class Clipboard(val from: String, val fromName: String, val payload: ClipboardPayload) : Msg()
    data class Kick(val reason: String?) : Msg()
    /** 解散通知：成员收到后 0.8s 自行退出，不回发 */
    object Dissolve : Msg()
    /** 转让第一步：群主 → 被转让者 */
    data class TransferOffer(val pin: String) : Msg()
    /** 转让第二步：被转让者 → 群主（通过原 ws） */
    data class TransferReady(val addr: String) : Msg()
    /** 转让失败（迟到 ready）：目标静默关闭 standby 服务 */
    object TransferError : Msg()
    /** 转让第三步：群主 → 全员迁移 */
    data class NewService(val addr: String, val pin: String, val owner: DeviceInfo) : Msg()
    /** 文件共享①：文件夹新增 */
    data class FileAdd(val from: String, val fromName: String, val file: SharedFile) : Msg()
    /** 文件共享②：删除自己文件夹内条目 */
    data class FileRemove(val from: String, val fromName: String, val fileIds: List<String>) : Msg()
    /** 传输中心①：任务诞生（下载方 → 群主，queuing） */
    data class FileTaskAdd(val task: TransferTask) : Msg()
    /** 传输中心②：供种方确认排队 */
    data class FileTaskQueued(val taskId: String) : Msg()
    /** 传输中心③：供种方开始发送 */
    data class FileTaskStarted(val taskId: String) : Msg()
    /** 传输中心④：供种方节流进度 */
    data class FileTaskProgress(val taskId: String, val progress: Int, val transferred: Long) : Msg()
    /** 传输中心⑤：供种方完成 */
    data class FileTaskFinish(val taskId: String) : Msg()
    /** 传输中心⑥：失败（双方都报，幂等） */
    data class FileTaskFailed(val taskId: String, val error: String, val reporter: String) : Msg()
    /** 传输中心⑦：取消（群内任一成员 → 群主；仅 queuing/transferring 可取消） */
    data class FileTaskCancel(val taskId: String, val cancellerId: String, val cancellerName: String) : Msg()
    /** 传输中心⑧：群主广播全量任务数组 */
    data class FileTasks(val tasks: List<TransferTask>) : Msg()
    data class Encrypted(val data: String) : Msg()

    fun toJson(): String {
        val o = when (this) {
            is Join -> JSONObject().put("type", "join").put("pin", pin).put("device", device.toJson())
            is JoinResult -> JSONObject().put("type", "join_result").put("ok", ok)
                .putOpt("reason", reason).put("members", members.map { it.toJson() }.let { org.json.JSONArray(it) })
            is Ping -> JSONObject().put("type", "ping").put("device", device.toJson())
                .put("seq", seq).put("ts_ms", tsMs)
            is Pong -> JSONObject().put("type", "pong")
                .put("members", org.json.JSONArray(members.map { it.toJson() }))
                .put("seq", seq).put("ts_ms", tsMs)
            is Members -> JSONObject().put("type", "members")
                .put("members", org.json.JSONArray(members.map { it.toJson() }))
            is Clipboard -> JSONObject().put("type", "clipboard").put("from", from)
                .put("from_name", fromName).put("payload", payload.toJson())
            is Kick -> JSONObject().put("type", "kick").putOpt("reason", reason)
            is Dissolve -> JSONObject().put("type", "dissolve")
            is TransferOffer -> JSONObject().put("type", "transfer_offer").put("pin", pin)
            is TransferReady -> JSONObject().put("type", "transfer_ready").put("addr", addr)
            is TransferError -> JSONObject().put("type", "transfer_error")
            is NewService -> JSONObject().put("type", "new_service").put("addr", addr).put("pin", pin)
                .put("owner", owner.toJson())
            is FileAdd -> JSONObject().put("type", "file_add").put("from", from)
                .put("from_name", fromName).put("file", file.toJson())
            is FileRemove -> JSONObject().put("type", "file_remove").put("from", from)
                .put("from_name", fromName).put("file_ids", org.json.JSONArray(fileIds))
            is FileTaskAdd -> JSONObject().put("type", "file_task_add").put("task", task.toJson())
            is FileTaskQueued -> JSONObject().put("type", "file_task_queued").put("task_id", taskId)
            is FileTaskStarted -> JSONObject().put("type", "file_task_started").put("task_id", taskId)
            is FileTaskProgress -> JSONObject().put("type", "file_task_progress")
                .put("task_id", taskId).put("progress", progress).put("transferred", transferred)
            is FileTaskFinish -> JSONObject().put("type", "file_task_finish").put("task_id", taskId)
            is FileTaskFailed -> JSONObject().put("type", "file_task_failed")
                .put("task_id", taskId).put("error", error).put("reporter", reporter)
            is FileTaskCancel -> JSONObject().put("type", "file_task_cancel")
                .put("task_id", taskId).put("canceller_id", cancellerId).put("canceller_name", cancellerName)
            is FileTasks -> JSONObject().put("type", "file_tasks")
                .put("tasks", org.json.JSONArray(tasks.map { it.toJson() }))
            is Encrypted -> JSONObject().put("type", "encrypted").put("data", data)
        }
        return o.toString()
    }

    /** 打包为加密信封。 */
    fun sealed(cipher: KnotCipher): Msg = Encrypted(cipher.encrypt(toJson()))

    companion object {
        fun parse(text: String): Msg? {
            return try {
                val o = JSONObject(text)
                when (o.getString("type")) {
                    "join" -> Join(o.getString("pin"), DeviceInfo.fromJson(o.getJSONObject("device")))
                    "join_result" -> JoinResult(
                        o.getBoolean("ok"),
                        if (o.has("reason")) o.getString("reason") else null,
                        o.optJSONArray("members")?.devices() ?: emptyList(),
                    )
                    "ping" -> Ping(DeviceInfo.fromJson(o.getJSONObject("device")), o.getLong("seq"), o.getLong("ts_ms"))
                    "pong" -> Pong(
                        o.optJSONArray("members")?.devices() ?: emptyList(),
                        o.getLong("seq"), o.getLong("ts_ms"),
                    )
                    "members" -> Members(o.getJSONArray("members").devices())
                    "clipboard" -> Clipboard(
                        o.getString("from"), o.getString("from_name"),
                        o.getJSONObject("payload").let {
                            ClipboardPayload(it.getString("kind"), it.optString("text"), it.getString("hash"))
                        },
                    )
                    "kick" -> Kick(if (o.has("reason")) o.getString("reason") else null)
                    "dissolve" -> Dissolve
                    "transfer_offer" -> TransferOffer(o.getString("pin"))
                    "transfer_ready" -> TransferReady(o.getString("addr"))
                    "transfer_error" -> TransferError
                    "new_service" -> NewService(
                        o.getString("addr"), o.getString("pin"),
                        DeviceInfo.fromJson(o.getJSONObject("owner")),
                    )
                    "file_add" -> FileAdd(
                        o.getString("from"), o.getString("from_name"),
                        SharedFile.fromJson(o.getJSONObject("file")),
                    )
                    "file_remove" -> FileRemove(
                        o.getString("from"), o.getString("from_name"),
                        o.getJSONArray("file_ids").let { arr -> (0 until arr.length()).map { i -> arr.getString(i) } },
                    )
                    "file_task_add" -> FileTaskAdd(TransferTask.fromJson(o.getJSONObject("task")))
                    "file_task_queued" -> FileTaskQueued(o.getString("task_id"))
                    "file_task_started" -> FileTaskStarted(o.getString("task_id"))
                    "file_task_progress" -> FileTaskProgress(
                        o.getString("task_id"), o.optInt("progress"), o.optLong("transferred"),
                    )
                    "file_task_finish" -> FileTaskFinish(o.getString("task_id"))
                    "file_task_failed" -> FileTaskFailed(
                        o.getString("task_id"), o.optString("error"), o.getString("reporter"),
                    )
                    "file_task_cancel" -> FileTaskCancel(
                        o.getString("task_id"), o.getString("canceller_id"), o.getString("canceller_name"),
                    )
                    "file_tasks" -> FileTasks(TransferTask.listFromJson(o.getJSONArray("tasks")))
                    "encrypted" -> Encrypted(o.getString("data"))
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun org.json.JSONArray.devices() =
            (0 until length()).map { i -> DeviceInfo.fromJson(getJSONObject(i)) }
    }
}

/** 供种端引擎向上抛的任务事件（与 knot-core TaskEvent 对齐）。 */
sealed class TaskEvent {
    data class Queued(val taskId: String) : TaskEvent()
    data class Started(val taskId: String) : TaskEvent()
    data class Progress(val taskId: String, val transferred: Long, val total: Long) : TaskEvent()
    data class Done(val taskId: String) : TaskEvent()
    data class Failed(val taskId: String, val error: String) : TaskEvent()
}

/** AES-256-GCM，密钥 = SHA-256("knotyup-v1|PIN")，与 knot-core crypto.rs 一致。 */
class KnotCipher(pin: String) {
    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest("knotyup-v1|$pin".toByteArray()),
        "AES",
    )

    fun encrypt(plaintext: String): String {
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(plaintext.toByteArray())
        return android.util.Base64.encodeToString(nonce + ct, android.util.Base64.NO_WRAP)
    }

    fun decrypt(data: String): String? {
        return try {
            val buf = android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
            if (buf.size < 12) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, buf.copyOfRange(0, 12)))
            String(cipher.doFinal(buf.copyOfRange(12, buf.size)))
        } catch (_: Exception) {
            null
        }
    }

    /** 文件分块加密：随机 12B nonce，返回 nonce || ciphertext（与 knot-core encrypt_chunk 一致）。 */
    fun encryptChunk(plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return nonce + cipher.doFinal(plain)
    }

    /** 文件分块解密：入参 nonce || ciphertext。 */
    fun decryptChunk(data: ByteArray): ByteArray? {
        return try {
            if (data.size < 12) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12)))
            cipher.doFinal(data.copyOfRange(12, data.size))
        } catch (_: Exception) {
            null
        }
    }
}

fun textHash(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8)
        .joinToString("") { "%02x".format(it) }
