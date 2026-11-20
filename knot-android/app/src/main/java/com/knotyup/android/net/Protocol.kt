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
    /** 成员身份：owner | member。服务端权威赋值；join 上报省略 */
    val role: String? = null,
) {
    val isOwner: Boolean get() = role == "owner"

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("platform", platform)
        addr?.let { put("addr", it) }
        fileAddr?.let { put("file_addr", it) }
        role?.let { put("role", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = DeviceInfo(
            o.getString("id"), o.getString("name"), o.optString("platform", "android"),
            if (o.has("addr")) o.getString("addr") else null,
            if (o.has("file_addr")) o.getString("file_addr") else null,
            if (o.has("role")) o.getString("role") else null,
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

sealed class Msg {
    data class Join(val pin: String, val device: DeviceInfo) : Msg()
    data class JoinResult(val ok: Boolean, val reason: String?, val members: List<DeviceInfo>) : Msg()
    data class Members(val members: List<DeviceInfo>) : Msg()
    data class Ping(val device: DeviceInfo, val seq: Long, val tsMs: Long) : Msg()
    /** 心跳回执：成员表快照即状态同步（群主身份看 role 字段） */
    data class Pong(val members: List<DeviceInfo>, val seq: Long, val tsMs: Long) : Msg()
    /** 剪贴板：from = 发送者 device.id，接收端 from == 自己 id 时忽略（回声去重） */
    data class Clipboard(val from: String, val payload: ClipboardPayload) : Msg()
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
    /** 文件共享①：入群全量上报自己文件夹 */
    data class FileAnnounce(val from: String, val files: List<SharedFile>) : Msg()
    /** 文件共享②：群主下发全量列表快照（按 device_id 分组） */
    data class FileList(val files: Map<String, List<SharedFile>>) : Msg()
    /** 文件共享③：文件夹新增（上传或下载完成共用） */
    data class FileAdd(val from: String, val file: SharedFile) : Msg()
    /** 文件共享④：删除自己文件夹内条目 */
    data class FileRemove(val from: String, val fileIds: List<String>) : Msg()
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
            is Clipboard -> JSONObject().put("type", "clipboard").put("from", from).put("payload", payload.toJson())
            is Kick -> JSONObject().put("type", "kick").putOpt("reason", reason)
            is Dissolve -> JSONObject().put("type", "dissolve")
            is TransferOffer -> JSONObject().put("type", "transfer_offer").put("pin", pin)
            is TransferReady -> JSONObject().put("type", "transfer_ready").put("addr", addr)
            is TransferError -> JSONObject().put("type", "transfer_error")
            is NewService -> JSONObject().put("type", "new_service").put("addr", addr).put("pin", pin)
                .put("owner", owner.toJson())
            is FileAnnounce -> JSONObject().put("type", "file_announce").put("from", from)
                .put("files", org.json.JSONArray(files.map { it.toJson() }))
            is FileList -> JSONObject().put("type", "file_list").put("files",
                org.json.JSONObject().also { obj ->
                    files.forEach { (dev, list) -> obj.put(dev, org.json.JSONArray(list.map { it.toJson() })) }
                })
            is FileAdd -> JSONObject().put("type", "file_add").put("from", from).put("file", file.toJson())
            is FileRemove -> JSONObject().put("type", "file_remove").put("from", from)
                .put("file_ids", org.json.JSONArray(fileIds))
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
                        o.getString("from"),
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
                    "file_announce" -> FileAnnounce(
                        o.getString("from"), SharedFile.listFromJson(o.getJSONArray("files")),
                    )
                    "file_list" -> o.getJSONObject("files").let { obj ->
                        FileList(obj.keys().asSequence().associate { dev ->
                            dev to SharedFile.listFromJson(obj.getJSONArray(dev))
                        })
                    }
                    "file_add" -> FileAdd(o.getString("from"), SharedFile.fromJson(o.getJSONObject("file")))
                    "file_remove" -> FileRemove(
                        o.getString("from"),
                        o.getJSONArray("file_ids").let { arr -> (0 until arr.length()).map { i -> arr.getString(i) } },
                    )
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
