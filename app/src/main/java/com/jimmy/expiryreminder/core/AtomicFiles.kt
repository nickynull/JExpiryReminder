package com.jimmy.expiryreminder.core

import java.io.File
import java.io.FileOutputStream

/**
 * 关键数据文件的原子写入。
 *
 * `data.json` 是唯一数据源：直接 `writeText` 覆盖时，写一半掉电、存储写满或进程被杀
 * 都会留下截断的 JSON，下次启动解析失败就等于全部物品丢失。
 * 这里统一「写临时文件 → fsync → 覆盖前留一份 .bak → rename」，
 * 任意一步失败旧文件都还在，损坏时还能从 `.bak` 扶正。
 */
object AtomicFiles {

    const val BAK_SUFFIX = ".bak"
    private const val TMP_SUFFIX = ".tmp"

    fun writeText(target: File, text: String, keepBackup: Boolean = true) {
        write(target, text.toByteArray(Charsets.UTF_8), keepBackup)
    }

    fun write(target: File, bytes: ByteArray, keepBackup: Boolean = true) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            // 先真正落盘再 rename：否则崩溃后可能 rename 出一个长度为 0 的文件
            runCatching { out.fd.sync() }
        }
        if (keepBackup && target.exists() && target.length() > 0) {
            runCatching {
                target.copyTo(File(target.parentFile, target.name + BAK_SUFFIX), overwrite = true)
            }
        }
        if (!tmp.renameTo(target)) {
            // 个别文件系统不支持覆盖式 rename，退化为复制后删除
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** 备份文件路径（不存在时返回 File，由调用方判断 exists）。 */
    fun backupOf(target: File): File = File(target.parentFile, target.name + BAK_SUFFIX)
}
