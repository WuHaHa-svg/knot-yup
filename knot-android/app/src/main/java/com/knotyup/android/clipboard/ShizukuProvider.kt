package com.knotyup.android.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.IBinder
import android.os.Parcel

/**
 * Shizuku IClipboard binder 代理（M2.2）：shell uid 豁免「焦点窗口才能读写剪贴板」限制。
 *
 * 原理：Shizuku 拿到 shell uid 的 clipboard 服务 binder → 本地手工构造 Parcel transact。
 * IClipboard 是 hidden 接口，事务码 = AOSP IClipboard.aidl 中方法的声明序（跨版本会变），
 * 下表已逐版本核对 AOSP 源码（10/11/12/13/14/15）：
 *
 * | API   | setPrimaryClip | getPrimaryClip | getDesc | 参数（clip 之后）                      |
 * |-------|----------------|----------------|---------|----------------------------------------|
 * | 29-30 | 1              | 3              | 4       | pkg, userId                            |
 * | 31-33 | 1              | 4              | 5       | pkg, userId（+插入 setPrimaryClipAsPackage）|
 * | 34+   | 1              | 4              | 5       | pkg, attributionTag, userId, deviceId  |
 *
 * marshalling 风格也随 aidl 编译器换代：API ≤30 用 writeParcelable/readParcelable，
 * API ≥31 用 writeTypedObject/readTypedObject（线上格式不同，不可混用）。
 *
 * 调用方约定：方法内是阻塞 binder 调用，禁止主线程直接调用（经 ClipboardCapability 的
 * suspend 接口自动切 IO 线程）。任何失败上抛，由 ClipboardCapability 降级前台模式。
 */
object ShizukuProvider {

    private const val DESCRIPTOR = "android.content.IClipboard"

    /**
     * callingPackage 必须属于调用方 uid，否则服务报 "Unknown package" 拒绝。
     * 经 Shizuku 代理时服务看到的 uid 是 shell(2000)——shell 拥有 com.android.shell。
     */
    private const val SHELL_PKG = "com.android.shell"

    /** 单用户设备（手机）固定 user 0；Shizuku 也以 user 0 运行。 */
    private const val USER_ID = 0

    private data class Codes(val set: Int, val get: Int, val desc: Int)

    private fun codes(): Codes = when {
        Build.VERSION.SDK_INT <= 30 -> Codes(1, 3, 4)
        else -> Codes(1, 4, 5) // 31+ 插入 setPrimaryClipAsPackage 导致后移
    }

    /** clipboard 服务 binder（阻塞，需 IO 线程）。
     *  进程内缓存：SystemServiceHelper 每次查询都要与 Shizuku 服务往返（延迟大且不稳），
     *  轮询场景下放大"读到中间态"的竞态窗口；transact 失败时置空重取。 */
    @Volatile
    private var cachedBinder: IBinder? = null

    private fun binder(): IBinder {
        cachedBinder?.let { return it }
        return rikka.shizuku.SystemServiceHelper.getSystemService("clipboard").also { cachedBinder = it }
    }

    /** transact 封装：binder 失效（Shizuku 重启等）时清缓存重取，下次调用自动恢复。 */
    private fun transact(binder: IBinder, code: Int, data: Parcel, reply: Parcel) {
        try {
            binder.transact(code, data, reply, 0)
        } catch (e: Exception) {
            cachedBinder = null
            throw e
        }
    }

    /** 后台读取剪贴板文本（无则 null；Shizuku 不可用/调用失败上抛）。 */
    fun getText(): String? {
        val c = codes()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(SHELL_PKG)
            if (Build.VERSION.SDK_INT >= 34) {
                data.writeString(null) // attributionTag（shell 无归属标签）
                data.writeInt(USER_ID)
                data.writeInt(0)      // deviceId：Context.DEVICE_ID_DEFAULT
            } else {
                data.writeInt(USER_ID)
            }
            transact(binder(), c.get, data, reply)
            reply.readException()
            val clip = if (Build.VERSION.SDK_INT >= 31) {
                reply.readTypedObject(ClipData.CREATOR)
            } else {
                @Suppress("DEPRECATION")
                reply.readParcelable(ClipData::class.java.classLoader)
            }
            // coerceToText(null)：纯文本剪贴板不依赖 context；意图类条目可能异常 → 按无文本处理
            clip?.getItemAt(0)?.let { runCatching { it.coerceToText(null)?.toString() }.getOrNull() }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * 剪贴板摘要指纹（轮询用，避免每次拉全文）：无剪贴板返回 null，有则返回
     * description 的 timestamp+hashCode 组合。调用失败上抛。
     */
    fun descriptionFingerprint(): String? {
        val c = codes()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(SHELL_PKG)
            if (Build.VERSION.SDK_INT >= 34) {
                data.writeString(null)
                data.writeInt(USER_ID)
                data.writeInt(0)
            } else {
                data.writeInt(USER_ID)
            }
            transact(binder(), c.desc, data, reply)
            reply.readException()
            val desc = if (Build.VERSION.SDK_INT >= 31) {
                reply.readTypedObject(ClipDescription.CREATOR)
            } else {
                reply.readParcelable(ClipDescription::class.java.classLoader)
            }
            desc?.let { "${it.timestamp}/${it.hashCode()}" }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** 后台写入剪贴板（失败上抛）。 */
    fun setText(text: String) {
        val c = codes()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (Build.VERSION.SDK_INT >= 31) {
                data.writeTypedObject(ClipData.newPlainText("knotyup", text), 0)
            } else {
                data.writeParcelable(ClipData.newPlainText("knotyup", text), 0)
            }
            data.writeString(SHELL_PKG)
            if (Build.VERSION.SDK_INT >= 34) {
                data.writeString(null)
                data.writeInt(USER_ID)
                data.writeInt(0)
            } else {
                data.writeInt(USER_ID)
            }
            transact(binder(), c.set, data, reply)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
