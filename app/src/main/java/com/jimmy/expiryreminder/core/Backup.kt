package com.jimmy.expiryreminder.core

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份导出/导入：打包 `data.json` + 图片文件 + manifest。
 * 备份目录结构：`modules/expiry/...`。
 */
object Backup {

    const val APP_NAME = "JExpiryReminder"
    const val BACKUP_EXT = "zip"

    /** 允许的备份来源：本应用（额外兼容历史版本导出的备份）。 */
    val KNOWN_SOURCES = setOf(APP_NAME, "JToolsBox")

    data class Manifest(
        val app: String,
        val schema: Int,
        val appVersion: String,
        val exportedAt: String,
        val modules: List<String>,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("app", app)
            .put("schema", schema)
            .put("appVersion", appVersion)
            .put("exportedAt", exportedAt)
            .put("modules", JSONArray(modules))
            .put("includeSettings", false)

        companion object {
            fun fromJson(raw: Any?): Manifest? {
                if (raw !is JSONObject) return null
                val app = raw.optStringOrNull("app") ?: return null
                return Manifest(
                    app = app,
                    schema = raw.optIntOrNull("schema") ?: 1,
                    appVersion = raw.optStringOrNull("appVersion") ?: "",
                    exportedAt = raw.optStringOrNull("exportedAt") ?: "",
                    modules = raw.optJSONArray("modules")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i -> arr.opt(i) as? String }
                    } ?: emptyList(),
                )
            }
        }
    }

    class BackupException(message: String) : Exception(message)

    /** 从备份 zip 的 key 里提取相对模块目录的路径；不匹配返回空串。 */
    fun relativeOf(key: String): String {
        val prefix = "${ImageStore.MODULE_DIR_NAME}/"
        return when {
            key.startsWith(prefix) -> key.substring(prefix.length)
            key == "data.json" -> key // 兼容裸 data.json
            else -> ""
        }
    }

    fun backupFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "$APP_NAME-expiry-$date.zip"
    }

    /** 导出当前全部数据（含图片）为 zip，写入用户通过 SAF 选择的 uri。 */
    fun exportTo(context: Context, uri: Uri, store: ExpiryStore) {
        // 先落盘一次，保证 data.json 与内存一致
        val dataFile = ImageStore.dataFile(context)
        dataFile.parentFile?.mkdirs()
        if (!dataFile.exists()) {
            dataFile.writeText(JSONObject().put("version", 1).put("items", JSONArray()).toString())
        }

        context.contentResolver.openOutputStream(uri)?.use { rawOut ->
            ZipOutputStream(rawOut.buffered()).use { zip ->
                // 空数据也要有 data.json，否则导入后读不到结构
                putFile(zip, "${ImageStore.MODULE_DIR_NAME}/data.json", dataFile, storeMode = true)

                // 图片本来就是压缩格式，不再二次压缩（STORE）
                val imagesDir = ImageStore.imagesDir(context)
                imagesDir.listFiles()?.sortedBy { it.name }?.forEach { img ->
                    if (img.isFile) {
                        putFile(zip, "${ImageStore.MODULE_DIR_NAME}/images/${img.name}", img, storeMode = true)
                    }
                }

                val manifest = Manifest(
                    app = APP_NAME,
                    schema = 1,
                    appVersion = "1.0.0",
                    exportedAt = Dates.nowIsoTimestamp(),
                    modules = listOf("expiry"),
                )
                val bytes = manifest.toJson().toString(2).toByteArray(Charsets.UTF_8)
                val entry = ZipEntry("manifest.json")
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        } ?: throw BackupException("无法写入所选位置")
    }

    private fun putFile(zip: ZipOutputStream, name: String, file: File, storeMode: Boolean) {
        val entry = ZipEntry(name)
        if (storeMode) entry.method = ZipEntry.STORED
        // STORED 需要预先填好 size/crc
        entry.size = file.length()
        entry.crc = file.checksumCrc32()
        zip.putNextEntry(entry)
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun File.checksumCrc32(): Long {
        val crc = java.util.zip.CRC32()
        inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }

    /** 读取并解析用户选择的备份文件；格式不对抛 BackupException。 */
    fun parseFrom(context: Context, uri: Uri): ParsedBackup {
        val files = LinkedHashMap<String, ByteArray>()
        var manifest: Manifest? = null
        context.contentResolver.openInputStream(uri)?.use { rawIn ->
            ZipInputStream(rawIn.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val bytes = zip.readBytes()
                        if (entry.name == "manifest.json") {
                            manifest = Manifest.fromJson(
                                try {
                                    JSONObject(String(bytes, Charsets.UTF_8))
                                } catch (_: Exception) {
                                    null
                                }
                            )
                        } else {
                            files[entry.name] = bytes
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: throw BackupException("无法读取所选文件")

        val m = manifest ?: throw BackupException(
            "备份文件缺少 manifest.json，可能不是 JExpiryReminder 备份"
        )
        if (m.app !in KNOWN_SOURCES) {
            throw BackupException("备份来源不受支持（仅支持 JExpiryReminder 备份）")
        }
        return ParsedBackup(manifest = m, files = files)
    }

    class ParsedBackup(val manifest: Manifest, val files: Map<String, ByteArray>)

    enum class ImportMode { replace, merge }

    data class ImportResult(val replacedItems: Int, val totalItems: Int)

    /** 应用备份。覆盖模式先清空再整包写入；合并模式按 id 合并、图片只增不减。 */
    suspend fun applyBackup(
        context: Context,
        store: ExpiryStore,
        payload: ParsedBackup,
        mode: ImportMode,
    ): ImportResult {
        // 找到 data.json（兼容 `modules/expiry/data.json` 与裸 `data.json`）
        val dataEntry = payload.files.entries.firstOrNull { relativeOf(it.key) == "data.json" }
            ?: throw BackupException("备份里没有 data.json")
        val dataJson = String(dataEntry.value, Charsets.UTF_8)
        val parsedData: JSONObject = try {
            JSONObject(dataJson)
        } catch (_: Exception) {
            throw BackupException("data.json 无法解析")
        }

        fun writeImages() {
            for ((key, bytes) in payload.files) {
                val relative = relativeOf(key)
                if (relative.isEmpty() || relative == "data.json") continue
                val safe = normalizeImagePath(relative)
                if (safe.isEmpty()) continue
                val file = ImageStore.imageFile(context, safe)
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }
        }

        if (mode == ImportMode.replace) {
            // 整目录替换：先清空数据与图片，避免旧图片变成没人引用的孤儿文件
            store.clearItems(context)
            ImageStore.clearExceptData(context)
            writeImages()
            ImageStore.dataFile(context).writeText(dataJson)
            store.reload(context)
        } else {
            // 合并：图片按文件名落盘，data.json 走 id 级合并
            writeImages()
            val existingFile = ImageStore.dataFile(context)
            if (existingFile.exists()) {
                try {
                    val existing = JSONObject(existingFile.readText())
                    val merged = ExpiryStore.mergeValue(existing, parsedData)
                    val mergedText =
                        if (merged is JSONObject) merged.toString(2) else dataJson
                    store.applyMergedDataJson(context, mergedText)
                    return ImportResult(
                        replacedItems = parsedData.optJSONArray("items")?.length() ?: 0,
                        totalItems = store.items.size,
                    )
                } catch (_: Exception) {
                    // 解析失败退化为直接覆盖
                }
            }
            ImageStore.dataFile(context).writeText(dataJson)
            store.reload(context)
        }
        return ImportResult(
            replacedItems = parsedData.optJSONArray("items")?.length() ?: 0,
            totalItems = store.items.size,
        )
    }
}
