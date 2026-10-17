package com.labteto.dshmobile.ui

import com.labteto.dshmobile.ui.sidebar.SidebarAvatarStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Sidebar avatar storage remains local after retiring remote session infrastructure. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SidebarAvatarEntryPoint {
    fun sidebarAvatarStore(): SidebarAvatarStore
}
