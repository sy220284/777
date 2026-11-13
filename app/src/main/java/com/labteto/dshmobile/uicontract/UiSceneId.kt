package com.labteto.dshmobile.uicontract

/**
 * Known native app destination stored in a user-facing UI document.
 * Not a scene registry or an interchangeable UI Pack contract.
 */
@JvmInline
value class UiSceneId(val value: String) {
    init { require(value.isNotBlank()) }
}
