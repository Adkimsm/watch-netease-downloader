package io.github.adkimsm.neteasedownloader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 应用唯一的色板来源。除本文件外,业务 UI 不应出现硬编码 Color(0x...)。
 *
 * 深色手表屏的层级靠"表面抬升"表达,而不是阴影(小屏阴影几乎不可见且更耗电)。
 */

// ---- 品牌 ----
/** 网易云红,主操作与强调 */
val BrandRed = Color(0xFFC20C0C)
val BrandRedPressed = Color(0xFF9A0909)

/** 低透明度品牌红:进度条轨道、选中底色 */
val BrandRedMuted = Color(0x33C20C0C)

// ---- 表面抬升 ----
/** 页面底 */
val SurfaceLevel0 = Color(0xFF000000)

/** 卡片 / 分组 */
val SurfaceLevel1 = Color(0xFF121212)

/** 按钮 / 输入类控件 */
val SurfaceLevel2 = Color(0xFF1E1E1E)

/** 分隔块、骨架屏底块、禁用底 */
val SurfaceLevel3 = Color(0xFF2A2A2A)

// ---- 文字 ----
val TextPrimary = Color(0xFFECEFF1)
val TextSecondary = Color(0xFFB0BEC5)
val TextDisabled = Color(0xFF6B7278)
val Divider = Color(0xFF2E2E2E)

// ---- 状态语义 ----
// 状态一律「色 + 图标 + 文案」三重编码,不单靠颜色传达。
val StateOk = Color(0xFF4CAF50)
val StateWarn = Color(0xFFFFB300)
val StateError = Color(0xFFE53935)
val StateInfo = Color(0xFF42A5F5)

/** 等待中(网络抖动等非错误态) */
val StatePending = TextDisabled
