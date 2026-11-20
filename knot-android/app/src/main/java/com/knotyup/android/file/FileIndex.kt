package com.knotyup.android.file

import android.content.Context
import com.knotyup.android.net.SharedFile
import org.json.JSONArray
import org.json.JSONObject

/** 本地清单条目：网络元数据 + 本地路径（path 仅本机用，绝不上报）。 */
data class LocalFile(val file: SharedFile, val path: String) {
    fun toJson() = JSONObject().apply {
        put("file", file.toJson())
        put("path", path)
    }

    companion object {
        fun fromJson(o: JSONObject) = LocalFile(
            SharedFile.fromJson(o.getJSONObject("file")),
            o.getString("path"),
        )
    }
}

/** 本地共享清单持久化（SharedPreferences 存 JSON 数组）。设备级、跨群复用。 */
class FileIndex(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("knotyup_files", Context.MODE_PRIVATE)

    fun all(): List<LocalFile> {
        val arr = try {
            JSONArray(prefs.getString(KEY, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        return (0 until arr.length()).mapNotNull { i ->
            try { LocalFile.fromJson(arr.getJSONObject(i)) } catch (_: Exception) { null }
        }
    }

    fun sharedFiles(): List<SharedFile> = all().map { it.file }

    fun add(entry: LocalFile) {
        val list = all().toMutableList()
        list.removeAll { it.file.fileId == entry.file.fileId }
        list.add(entry)
        persist(list)
    }

    fun remove(fileIds: List<String>) {
        persist(all().filterNot { it.file.fileId in fileIds })
    }

    fun pathOf(fileId: String): String? = all().firstOrNull { it.file.fileId == fileId }?.path

    private fun persist(list: List<LocalFile>) {
        val arr = JSONArray(list.map { it.toJson() })
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "index"
    }
}
