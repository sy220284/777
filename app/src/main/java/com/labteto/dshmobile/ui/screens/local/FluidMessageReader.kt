package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.components.SyEmptyState

import com.labteto.dshmobile.ui.shenyan.SyFullScreenDialog
import com.labteto.dshmobile.ui.shenyan.SyTopBar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.*
import com.labteto.dshmobile.ui.theme.*

/** Design 05: immersive reading occupies the display and keeps the conversation mounted beneath it. */
@Composable
internal fun FluidMessageReader(title: String, content: String, onDismiss: () -> Unit) {
    SyFullScreenDialog(onDismiss = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = DsTheme.colors.bgBase) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                SyTopBar(title, onBack = onDismiss, backContentDescription = stringResource(R.string.common_back))
                if (content.isBlank()) SyEmptyState(FeatherIcons.BookOpen,
                    stringResource(R.string.fluid_reader_empty), body = "", modifier = Modifier.weight(1f))
                else SelectionContainer(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(DsSpacing.comfortable)) {
                        MarkdownText(content, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
