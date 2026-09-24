package io.github.adkimsm.neteasedownloader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 统一圆角令牌:高级深色 + 圆角毛玻璃风格。
 *
 * 手表屏小,卡片圆角比手机更大以强化\"毛玻璃浮层\"感,但列表行圆角要克制,
 * 避免行内容被切掉。
 */
object AppShapes {
    /** 列表行 / 输入项:小圆角,内容不拥挤 */
    val Row = RoundedCornerShape(12.dp)

    /** 卡片 / 分组:中圆角 */
    val Card = RoundedCornerShape(16.dp)

    /** 按钮 / 对话框 / 浮层:大圆角(毛玻璃感) */
    val Control = RoundedCornerShape(20.dp)

    /** 圆形(播放键、封面、徽标点) */
    val Circle = RoundedCornerShape(50)

    /** 进度条/骨架块:胶囊形 */
    val Pill = RoundedCornerShape(50)
}
