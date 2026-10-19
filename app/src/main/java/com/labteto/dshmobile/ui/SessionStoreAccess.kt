package com.labteto.dshmobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.labteto.dshmobile.ui.sidebar.SidebarAvatarStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Sidebar avatar storage remains local after retiring remote session infrastructure. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SidebarAvatarEntryPoint {
    fun sidebarAvatarStore(): SidebarAvatarStore
}

@Composable
internal fun rememberSidebarAvatarStore(): SidebarAvatarStore {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SidebarAvatarEntryPoint::class.java).sidebarAvatarStore()
    }
}

