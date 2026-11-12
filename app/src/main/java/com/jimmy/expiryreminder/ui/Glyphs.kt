package com.jimmy.expiryreminder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * 相机图标。
 *
 * 项目只依赖 `material-icons-core`，里面没有 PhotoCamera；补 `material-icons-extended`
 * 会往 APK 里塞十几 MB（release 未开混淆，不会被裁掉）。这里直接画一个。
 *
 * @param holeColor 图标「挖空」部分用容器底色填，保证在不同底色上都像镂空。
 */
@Composable
fun CameraGlyph(
    color: Color,
    holeColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val s = size.minDimension
        // 机身
        drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.04f, s * 0.30f),
            size = Size(s * 0.92f, s * 0.56f),
            cornerRadius = CornerRadius(s * 0.16f),
        )
        // 顶部取景器凸起（宽 0.30s，居中于机身中线 0.5s）
        drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.35f, s * 0.15f),
            size = Size(s * 0.30f, s * 0.18f),
            cornerRadius = CornerRadius(s * 0.06f),
        )
        // 镜头：先用底色挖空，再描一圈环
        drawCircle(
            color = holeColor,
            radius = s * 0.17f,
            center = Offset(s * 0.5f, s * 0.58f),
        )
        drawCircle(
            color = color,
            radius = s * 0.115f,
            center = Offset(s * 0.5f, s * 0.58f),
            style = Stroke(width = s * 0.05f),
        )
    }
}
