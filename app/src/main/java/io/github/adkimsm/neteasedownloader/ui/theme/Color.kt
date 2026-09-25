package io.github.adkimsm.neteasedownloader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 应用唯一的色板来源。除本文件外,业务 UI 不应出现硬编码 Color(0x...)。
 *
 * 列表靠文字层级与细分隔线区分内容,只为少量独立卡片抬升底色。
 * 不为每一行添加底色、浅色描边或阴影。
 */

// ---- 品牌 ----
/** 网易云红,主操作与强调 */
val BrandRed = Color(0xFFE02424)
val BrandRedPressed = Color(0xFFB81D1D)

/** 低透明度品牌红:进度条轨道、选中/当前项底色 */
val BrandRedMuted = Color(0x26E02424)

// ---- 表面抬升 ----
/** 页面底:带暗红相的近黑 */
val SurfaceLevel0 = Color(0xFF0A0808)

/** 卡片 / 分组 */
val SurfaceLevel1 = Color(0xFF161313)

/** 卡片 / 浮层底(仅真实卡片使用;列表行一律纯背景) */
val SurfaceLevel2 = Color(0xFF211D1D)

/** 分隔块、骨架屏底块、禁用底 */
val SurfaceLevel3 = Color(0xFF2E2A2A)

/** 最高抬升:浮层 / 弹层底,比 SurfaceLevel2 再亮一档 */
val SurfaceLevel4 = Color(0xFF373232)

// ---- 文字 ----
val TextPrimary = Color(0xFFF2EDEB)
val TextSecondary = Color(0xFFB8AFA9)
val TextDisabled = Color(0xFF6E6662)
val Divider = Color(0xFF2B2626)

// ---- 状态语义 ----
// 状态一律「色 + 图标 + 文案」三重编码,不单靠颜色传达。
val StateOk = Color(0xFF4CAF50)
val StateWarn = Color(0xFFFFB300)
val StateError = Color(0xFFE53935)
val StateInfo = Color(0xFF42A5F5)

/** 等待中(网络抖动等非错误态) */
val StatePending = TextDisabled
