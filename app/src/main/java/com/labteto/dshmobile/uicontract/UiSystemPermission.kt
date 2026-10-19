package com.labteto.dshmobile.uicontract

/** Closed Android application permissions; these never grant an Agent or tool capability. */
enum class UiSystemPermission { NOTIFICATIONS, IMAGES, VIDEOS, AUDIO, SELECTED_VISUAL, CAMERA }

sealed interface UiSystemPermissionReceipt {
    data class Completed(val requested: Set<UiSystemPermission>, val granted: Set<UiSystemPermission>) : UiSystemPermissionReceipt
    data object Busy : UiSystemPermissionReceipt
    data object Interrupted : UiSystemPermissionReceipt
    data class Failed(val message: String?) : UiSystemPermissionReceipt
}
