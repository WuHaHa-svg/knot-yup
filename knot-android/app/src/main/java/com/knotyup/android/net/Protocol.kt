package com.knotyup.android.net

import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 与 knot-core/src/protocol.rs 对应的消息模型（JSON + 加密信封）。 */

data class DeviceInfo(
    val id: String,
    val name: String,
    val platform: String = "android",
    /** 本机可承接群主的地址（ip:port） */
    val addr: String? = null,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("platform", platform)
        addr?.let { put("addr", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = DeviceInfo(
            o.getString("id"), o.getString("name"), o.optString("platform", "android"),
            if (o.has("addr")) o.getString("addr") else null,
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
    data class Clipboard(val from: String, val payload: ClipboardPayload) : Msg()
    data class Kick(val reason: String?) : Msg()
    data class OwnerChange(val newOwner: DeviceInfo) : Msg()
    data class Encrypted(val data: String) : Msg()

    fun toJson(): String {
        val o = when (this) {
            is Join -> JSONObject().put("type", "join").put("pin", pin).put("device", device.toJson())
            is JoinResult -> JSONObject().put("type", "join_result").put("ok", ok)
                .putOpt("reason", reason).put("members", members.map { it.toJson() }.let { org.json.JSONArray(it) })
            is Members -> JSONObject().put("type", "members")
                .put("members", org.json.JSONArray(members.map { it.toJson() }))
            is Clipboard -> JSONObject().put("type", "clipboard").put("from", from).put("payload", payload.toJson())
            is Kick -> JSONObject().put("type", "kick").putOpt("reason", reason)
            is OwnerChange -> JSONObject().put("type", "owner_change").put("new_owner", newOwner.toJson())
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
                    "members" -> Members(o.getJSONArray("members").devices())
                    "clipboard" -> Clipboard(
                        o.getString("from"),
                        o.getJSONObject("payload").let {
                            ClipboardPayload(it.getString("kind"), it.optString("text"), it.getString("hash"))
                        },
                    )
                    "kick" -> Kick(if (o.has("reason")) o.getString("reason") else null)
                    "owner_change" -> OwnerChange(DeviceInfo.fromJson(o.getJSONObject("new_owner")))
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
