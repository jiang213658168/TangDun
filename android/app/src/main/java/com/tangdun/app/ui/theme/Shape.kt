package com.tangdun.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 糖盾 TangDun 形状系统 (v2.0)
 *
 * 设计理念:
 *  - 5 级圆角梯度, 不同场景用不同圆角
 *  - 统一圆角比例, 视觉一致性
 *  - 大圆角传递柔和/友好的感觉 (符合健康类 App 调性)
 */
val TangDunShapes = Shapes(
    // 极小圆角 - 标签/徽章/小按钮
    extraSmall = RoundedCornerShape(6.dp),
    // 小圆角 - 输入框/小卡片/列表项
    small      = RoundedCornerShape(12.dp),
    // 中圆角 - 标准卡片/按钮
    medium     = RoundedCornerShape(16.dp),
    // 大圆角 - 大卡片/Hero 卡片
    large      = RoundedCornerShape(24.dp),
    // 极大圆角 - 顶部 Banner/FAB 容器
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * 圆角使用指南:
 *  - extraSmall: Chip、Tag、小徽章
 *  - small: TextField、ListItem、小信息块
 *  - medium: 标准 Card、Button、Dialog
 *  - large: Hero 卡片、底部 Sheet
 *  - extraLarge: 全屏 Banner、特殊强调区
 */
