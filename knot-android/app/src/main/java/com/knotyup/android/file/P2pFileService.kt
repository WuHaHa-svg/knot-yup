package com.knotyup.android.file

import android.content.Context
import android.util.Log
import com.knotyup.android.net.KnotCipher
import com.knotyup.android.net.Proto
import com.knotyup.android.net.TaskEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.Semaphore

private const val TAG = "KnotFile"

/** HMAC-SHA256(pin, msg) 的 hex（与 knot-core file.rs auth_token 一致）。 */
private fun authToken(pin: String, msg: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(pin.toByteArray(), "HmacSHA256"))
    return mac.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** 下载结果元信息（上层生成新 file_id 写清单）。 */
data class P2pDownloadResult(val size: Long, val sha256: String)

/**
 * P2P 文件服务：设备级常驻供种 + 下载。协议与 knot-core/src/file.rs 逐字节一致。
 * PIN 可动态更新（入新群换 PIN，端口不变）；同一时刻只服务 1 个下载者（Semaphore 排队）。
 */
class P2pFileService(
    private val indexProvider: () -> FileIndex,
    private val localIdProvider: () -> String,
    private val contextProvider: () -> Context,
    private val onEvent: ((TaskEvent) -> Unit)? = null,
) {
    private val index: FileIndex get() = indexProvider()
    @Volatile
    private var pin: String = ""
    @Volatile
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(1)

    fun updatePin(pin: String) {
        this.pin = pin
    }

    /** 启动供种服务（幂等），返回实际端口。设备级常驻，不随离群停止。 */
    fun startServer(): Int {
        serverSocket?.let { return it.localPort }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("0.0.0.0", 0))
        serverSocket = ss
        scope.launch {
            while (!ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                scope.launch { handleClient(client) }
            }
        }
        Log.i(TAG, "供种服务启动 port=${ss.localPort}")
        return ss.localPort
    }

    fun stopServer() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            serveOne(socket)
        } catch (e: Exception) {
            Log.d(TAG, "供种会话结束: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun serveOne(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val reqLen = input.readInt()
        if (reqLen <= 0 || reqLen > 1024 * 1024) return
        val reqBytes = ByteArray(reqLen).also { input.readFully(it) }
        val req = JSONObject(String(reqBytes, Charsets.UTF_8))
        val srcFileId = req.getString("src_file_id")
        val from = req.getString("from")
        val to = req.getString("to")
        val token = req.getString("token")
        val taskId = req.getString("task_id") // 下载方生成，供种方据此上报任务状态
        var gateHeld = false
        // 供种侧拒绝：发错误帧给下载方 + 抛异常让下方 catch 统一上报 Failed
        fun reject(code: String): Nothing {
            val err = JSONObject().put("error", code).toString().toByteArray()
            output.writeInt(err.size); output.write(err); output.flush()
            throw IllegalStateException(code)
        }
        try {
            // 排队阶段：闸门被占时在这里等待（已知 task_id，先报 Queued）
            if (taskId.isNotEmpty()) onEvent?.invoke(TaskEvent.Queued(taskId))
            gate.acquire()
            gateHeld = true
            if (taskId.isNotEmpty()) onEvent?.invoke(TaskEvent.Started(taskId))

            val expect = authToken(pin, "$srcFileId|$from|$to")
            if (to != localIdProvider() || token != expect) reject("AUTH_FAILED")
            val path = index.pathOf(srcFileId) ?: reject("NOT_FOUND")
            // 清单里 path 可能是 content://（SAF）或普通文件路径，统一走 InputStream
            val size = sourceSize(path) ?: reject("NOT_FOUND")
            val sha = openSource(path)?.use { sha256Stream(it) } ?: reject("NOT_FOUND")
            val ok = JSONObject().put("ok", true).put("size", size).put("sha256", sha)
                .toString().toByteArray()
            output.writeInt(ok.size); output.write(ok); output.flush()

            val cipher = KnotCipher(pin)
            val fis = openSource(path) ?: reject("NOT_FOUND")
            fis.use { inputStream ->
                var idx = 0
                val buf = ByteArray(Proto.FILE_CHUNK_SIZE)
                while (true) {
                    val n = inputStream.read(buf)
                    if (n <= 0) break
                    val enc = cipher.encryptChunk(buf.copyOfRange(0, n))
                    output.writeInt(idx)
                    output.writeInt(enc.size)
                    output.write(enc)
                    output.flush()
                    val ack = input.readInt()
                    if (ack != idx) break
                    idx++
                    if (taskId.isNotEmpty()) {
                        val sent = idx.toLong() * Proto.FILE_CHUNK_SIZE
                        onEvent?.invoke(TaskEvent.Progress(taskId, sent.coerceAtMost(size), size))
                    }
                }
            }
            // 等下载端 done/error 控制帧
            try {
                val doneLen = input.readInt()
                if (doneLen in 1..1024) {
                    ByteArray(doneLen).also { input.readFully(it) }
                }
            } catch (_: Exception) {}
            if (taskId.isNotEmpty()) onEvent?.invoke(TaskEvent.Done(taskId))
        } catch (e: Exception) {
            // 供种侧失败上报（与下载侧形成双保险）
            if (taskId.isNotEmpty()) onEvent?.invoke(TaskEvent.Failed(taskId, e.message ?: "供种失败"))
            throw e
        } finally {
            if (gateHeld) gate.release()
        }
    }

    /**
     * 从供种者下载文件，逐块解密写入 sink，校验 sha256。
     * sink 由上层实现（桌面写文件 / Android 写 MediaStore）。
     */
    suspend fun download(
        addr: String,
        seedId: String,
        srcFileId: String,
        sink: OutputStream,
        onProgress: ((Long, Long) -> Unit)? = null,
        taskId: String = "",
    ): P2pDownloadResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val host = addr.substringBefore(":")
        val port = addr.substringAfter(":").toInt()
        val socket = Socket(host, port).apply { soTimeout = 30000 }
        socket.use { s ->
            val input = DataInputStream(s.getInputStream())
            val output = DataOutputStream(s.getOutputStream())
            val myId = localIdProvider()
            val req = JSONObject().apply {
                put("src_file_id", srcFileId)
                put("from", myId)
                put("to", seedId)
                put("token", authToken(pin, "$srcFileId|$myId|$seedId"))
                put("resume_from", 0)
                put("task_id", taskId)
            }.toString().toByteArray()
            output.writeInt(req.size); output.write(req); output.flush()

            val respLen = input.readInt()
            val resp = JSONObject(String(ByteArray(respLen).also { input.readFully(it) }, Charsets.UTF_8))
            if (!resp.optBoolean("ok", false)) {
                throw IllegalStateException("供种者拒绝: ${resp.optString("error")}")
            }
            val size = resp.getLong("size")
            val expectSha = resp.getString("sha256")
            val cipher = KnotCipher(pin)
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var idx = 0
            while (received < size) {
                val blockIdx = input.readInt()
                val encLen = input.readInt()
                if (encLen <= 0 || encLen > Proto.FILE_CHUNK_SIZE + 1024) {
                    throw IllegalStateException("块长度异常: $encLen")
                }
                val enc = ByteArray(encLen).also { input.readFully(it) }
                val plain = cipher.decryptChunk(enc) ?: throw IllegalStateException("解密块失败 idx=$blockIdx")
                sink.write(plain)
                digest.update(plain)
                received += plain.size
                output.writeInt(blockIdx); output.flush()
                onProgress?.invoke(received, size)
            }
            sink.flush()
            val gotSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (gotSha != expectSha) {
                val err = JSONObject().put("error", "HASH_MISMATCH").toString().toByteArray()
                output.writeInt(err.size); output.write(err); output.flush()
                throw IllegalStateException("sha256 校验失败")
            }
            val done = JSONObject().put("done", true).toString().toByteArray()
            output.writeInt(done.size); output.write(done); output.flush()
            P2pDownloadResult(size, gotSha)
        }
    }

    /**
     * 打开供种源文件：清单 path 可能是 content://（SAF）或普通文件路径。
     * 安卓共享走 SAF，存的是 content URI，必须用 ContentResolver 读，不能用 java.io.File。
     */
    private fun openSource(path: String): InputStream? = runCatching {
        if (path.startsWith("content://")) {
            contextProvider().contentResolver.openInputStream(android.net.Uri.parse(path))
        } else {
            val f = File(path)
            if (f.exists()) FileInputStream(f) else null
        }
    }.getOrNull()

    /** 供种源文件大小：content URI 用 AssetFileDescriptor.length，文件路径用 File.length。 */
    private fun sourceSize(path: String): Long? = runCatching {
        if (path.startsWith("content://")) {
            contextProvider().contentResolver
                .openAssetFileDescriptor(android.net.Uri.parse(path), "r")?.use { it.length }
        } else {
            val f = File(path)
            if (f.exists()) f.length() else null
        }
    }.getOrNull()

    /** 计算文件 sha256（供上传登记用，InputStream 来自 SAF URI）。 */
    fun sha256Stream(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
