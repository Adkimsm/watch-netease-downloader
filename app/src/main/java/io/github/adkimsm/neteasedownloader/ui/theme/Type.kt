package io.github.adkimsm.neteasedownloader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 手表字号阶梯。
 *
 * 手表视距比手机近(约 30cm),故整体比 Material 默认放大约一档。
 * 约定:单屏最多同时出现 3 级字号(标题 / 正文 / 辅助),避免小屏视觉噪声。
 */
val AppTypography = Typography(
    // 进度百分比等需要一眼读到的大数字
    displaySmall = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.W500,
    ),
    // 页面标题
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.W600,
    ),
    // 卡片主标题、歌单名
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.W600,
    ),
    // 按钮文案、主要信息
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    // 常规正文
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    // 辅助说明、时间戳
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // 状态徽标、进度阶段标签
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.W500,
        letterSpacing = 0.5.sp,
    ),
)
