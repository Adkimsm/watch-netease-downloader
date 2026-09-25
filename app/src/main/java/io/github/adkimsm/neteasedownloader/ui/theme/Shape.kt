package io.github.adkimsm.neteasedownloader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 卡片、控件与占位内容共用的圆角令牌。
 * 普通列表行不叠加圆角底色,改用细分隔线;圆角只保留在独立卡片和控件上。
 */
object AppShapes {
    /** 小型控件 / 状态提示 / 骨架占位 */
    val Row = RoundedCornerShape(12.dp)

    /** 卡片 / 分组:中圆角 */
    val Card = RoundedCornerShape(16.dp)

    /** 按钮 / 对话框 / 浮层 */
    val Control = RoundedCornerShape(20.dp)

    /** 圆形(播放键、封面、徽标点) */
    val Circle = RoundedCornerShape(50)

    /** 进度条/骨架块:胶囊形 */
    val Pill = RoundedCornerShape(50)
}
