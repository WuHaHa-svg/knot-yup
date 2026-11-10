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
}

data class DeviceInfo(
    val id: String,
    val name: String,
    val platform: String = "android",
    /** 本机 standby 服务地址（ip:port）；群主/未预留为 null */
    val addr: String? = null,
    /** 成员身份：owner | member。服务端权威赋值；join 上报省略 */
    val role: String? = null,
) {
    val isOwner: Boolean get() = role == "owner"

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("platform", platform)
        addr?.let { put("addr", it) }
        role?.let { put("role", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = DeviceInfo(
            o.getString("id"), o.getString("name"), o.optString("platform", "android"),
            if (o.has("addr")) o.getString("addr") else null,
            if (o.has("role")) o.getString("role") else null,
        )
    }
}

data class ClipboardPayload(val kind: String, val text: String, val hash: String) {
    fun toJson() = JSONObject().apply {
        put("kind", kind); put("text", text); put("hash", hash)
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
}

fun textHash(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8)
        .joinToString("") { "%02x".format(it) }
