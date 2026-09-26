package io.github.adkimsm.neteasedownloader.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * 动效令牌。
 *
 * 全应用**唯一**允许声明动画时长与规格的地方 —— 各屏不再手写 `tween(300)` 这类魔法数,
 * 与 [Spacing] / [Dimens] / [AppShapes] 同一套做法。
 *
 * 为什么比 Material 默认值短:
 * Material 的 300~400ms 是给手机屏幕定的。手表窗口只有 300~400dp,位移距离短得多,
 * 同样的时长会显得"发飘";且手表 CPU 弱,动画期间的重组开销要尽快结束。
 * 220ms 是位移的甜点值:能看清方向,又不会让用户等。
 *
 * 三个时长各有分工,不要互相替换:
 *  - [Nav]  整屏页面进出:位移 + 淡出,是唯一带方向感的动画
 *  - [Overlay] 浮层显隐(结果条 / mini 播放条):只做位移,必须比页面更快,
 *              否则会让人觉得"通知来得比操作还慢"
 *  - [Content] 同屏内容切换(骨架屏 → 列表):无方向,纯淡入淡出
 */
object Motion {

    // ---- 时长(毫秒) ----

    /** 整屏页面进出的时长 */
    const val NavDurationMs = 220

    /** 浮层(结果条 / mini 播放条)显隐的时长 */
    const val OverlayDurationMs = 180

    /** 同屏内容切换(骨架屏 → 真实列表)的时长 */
    const val ContentDurationMs = 160

    /**
     * 页面横向位移距离(dp)。
     *
     * **固定值而非按屏宽比例**:320px 圆形小表上按比例算出来的位移太小(30~40dp),
     * 方向感会消失;固定 48dp 在大屏上也不显突兀。动画只作用于页面容器,
     * 与页面内部的尺寸令牌(见 [WindowSizing])互不影响。
     */
    const val NavSlideDistanceDp = 48

    /** 模态页(同步预览 / 进度)上浮的距离(dp),比横向位移轻一档 */
    const val ModalRiseDistanceDp = 16

    // ---- 规格 ----

    /** 页面横向位移 */
    val NavSlideSpec = tween<IntOffset>(NavDurationMs, easing = FastOutSlowInEasing)

    /** 页面淡入淡出 */
    val NavFadeSpec = tween<Float>(NavDurationMs, easing = LinearOutSlowInEasing)

    /** 浮层显隐(位移与透明度共用) */
    val OverlaySpec = tween<Float>(OverlayDurationMs, easing = FastOutSlowInEasing)

    /** 同屏内容切换 */
    val ContentFadeSpec = tween<Float>(ContentDurationMs, easing = LinearOutSlowInEasing)
}
