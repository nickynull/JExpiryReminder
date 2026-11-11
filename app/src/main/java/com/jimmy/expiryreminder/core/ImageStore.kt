package com.jimmy.expiryreminder.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.File

/**
 * 图片与数据文件的落盘：
 * `modules/expiry/data.json` + `modules/expiry/images/<id>.jpg`。
 */
object ImageStore {
    const val APP_DIR_NAME = "JExpiryReminder"
    const val MODULE_DIR_NAME = "modules/expiry"
    const val IMAGE_DIR_NAME = "images"

    private var base: File? = null

    /** 数据根目录：`<应用内部存储>/JExpiryReminder/modules/expiry/`。 */
    fun baseDir(context: Context): File {
        base?.let { return it }
        val dir = File(File(context.filesDir, APP_DIR_NAME), MODULE_DIR_NAME)
        dir.mkdirs()
        base = dir
        return dir
    }

    fun dataFile(context: Context): File = File(baseDir(context), "data.json")

    fun imageFile(context: Context, relativePath: String): File =
        File(baseDir(context), relativePath)

    fun imagesDir(context: Context): File = File(baseDir(context), IMAGE_DIR_NAME)

    /** 把压缩后的图片字节写入数据目录，返回相对路径。 */
    fun saveImageBytes(context: Context, bytes: ByteArray, id: String): String {
        val path = newImagePath(id)
        val file = imageFile(context, path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return path
    }

    /** 把 v3 的内联 data URL 写成图片文件（旧数据迁移用）。 */
    fun saveDataUrl(context: Context, dataUrl: String, id: String): String {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) throw IllegalArgumentException("非法的 data URL")
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        return saveImageBytes(context, bytes, id)
    }

    /**
     * 把原始图片解码后等比缩放至最长边 ≤ 1280，再以 JPEG 85 保存。
     * 解码失败返回 null（交给调用方提示）。
     */
    fun saveCompressedImage(context: Context, raw: ByteArray, id: String): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxDim = 1280
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: return null
        val scaled = scaleToFit(decoded, maxDim)
        if (scaled !== decoded) decoded.recycle()

        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        scaled.recycle()
        return saveImageBytes(context, out.toByteArray(), id)
    }

    private fun scaleToFit(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val scale = maxOf(w, h).takeIf { it > maxDim }?.let { maxDim.toFloat() / it } ?: 1f
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true
        )
    }

    /** 删除图片文件；失败只忽略，不阻断主流程。 */
    fun removeImages(context: Context, paths: List<String>) {
        for (raw in paths) {
            val path = normalizeImagePath(raw)
            if (path.isEmpty()) continue
            try {
                imageFile(context, path).delete()
            } catch (_: Exception) {
            }
        }
    }

    /** 清空 images 目录所有文件（导入覆盖模式用）。 */
    fun clearExceptData(context: Context) {
        val dir = imagesDir(context)
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
