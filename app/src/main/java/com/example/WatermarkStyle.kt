package com.example

enum class WatermarkPosition {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
}

enum class WatermarkTheme {
    HIGH_DENSITY,   // 高密印章
    CLASSIC_SLATE,  // 经典简约
    CONSTRUCTION,   // 工程记录
    CYBER_TECH,     // 科技电子
    MINIMAL_BADGE   // 极简邮章
}

data class WatermarkConfig(
    val position: WatermarkPosition = WatermarkPosition.BOTTOM_LEFT,
    val theme: WatermarkTheme = WatermarkTheme.HIGH_DENSITY,
    val customText: String = "现场打卡拍照",
    val showDate: Boolean = true,
    val showLocation: Boolean = true,
    val showCoordinates: Boolean = true,
    val showCustomText: Boolean = true,
    val customLocation: String = "", // Used when the user modifies manually
    val badgeText: String = "已验证"
)
