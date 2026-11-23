package com.jimmy.expiryreminder.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 外部包（备份 zip、跨设备传输 zip）解包时的硬上限。
 *
 * 这些文件都不是本进程产出：备份可能来自任意 App 分享、传输包来自局域网对端，
 * 在没有上限的情况下一个几十 KB 的 zip 炸弹就能把进程撑到 OOM，
 * 而 `catch (Exception)` 接不住 `OutOfMemoryError`，表现为直接闪退。
 */
object IoLimits {

    /** 备份里单个条目（图片已压到 ≤1280px，8MB 足够）。 */
    const val MAX_BACKUP_ENTRY = 8L * 1024 * 1024
    /** 整包解压后的累计字节。 */
    const val MAX_BACKUP_TOTAL = 256L * 1024 * 1024
    const val MAX_BACKUP_ENTRIES = 2_000

    /** 传输包里单张图片。 */
    const val MAX_IMAGE_ENTRY = 12L * 1024 * 1024
    /** 传输包解压后的累计字节。 */
    const val MAX_IMAGE_TOTAL = 256L * 1024 * 1024
    const val MAX_TRANSFER_ENTRIES = 1_000

    /** manifest.json 只是元数据，不能让对端用一条巨型 JSON 拖死解析。 */
    const val MAX_MANIFEST = 4L * 1024 * 1024

    class TooLargeException(message: String) : IOException(message)

    /** 边读边计数，超过 [max] 立即抛异常，不会先把整个条目读完再判断。 */
    fun readLimited(input: InputStream, max: Long): ByteArray {
        val initial = minOf(max, 64L * 1024).toInt().coerceAtLeast(16)
        val out = ByteArrayOutputStream(initial)
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw TooLargeException("解压后内容超过上限")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
