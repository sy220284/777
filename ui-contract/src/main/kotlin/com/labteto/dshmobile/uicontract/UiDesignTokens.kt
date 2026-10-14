package com.labteto.dshmobile.uicontract

/** Pack-local semantic values. The Host does not interpret a pack's palette or component styles. */
sealed interface UiDesignToken {
    data class Color(val hex: String) : UiDesignToken {
        init { require(hex.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) }
    }
    data class Dimension(val value: Float, val unit: Unit) : UiDesignToken {
        enum class Unit { DP, SP }
        init { require(value.isFinite() && value >= 0) }
    }
    data class Duration(val milliseconds: Long) : UiDesignToken { init { require(milliseconds >= 0) } }
    data class FontWeight(val value: Int) : UiDesignToken { init { require(value in 1..1000) } }
}
data class UiDesignTokenManifest(val version: Int, val themes: Map<String, Map<String, UiDesignToken>>) {
    init {
        require(version == 1 && themes.isNotEmpty())
        require(themes.all { (name, roles) -> name.isNotBlank() && roles.isNotEmpty() && roles.keys.none(String::isBlank) })
    }
}
