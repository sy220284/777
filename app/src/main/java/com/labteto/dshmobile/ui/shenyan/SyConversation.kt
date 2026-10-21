package com.labteto.dshmobile.ui.shenyan

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.scaleIn
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.shenyan.designsystem.components.SyMaterialSurface
import com.shenyan.designsystem.components.SyMaterialTier
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.LocalSyReduceMotion
import com.shenyan.designsystem.foundation.SyMetric
import com.shenyan.designsystem.foundation.SyMotion
import com.shenyan.designsystem.foundation.LocalSyMotionTempo
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

const val SY_COMPOSER_FIELD_TAG = "ds-composer-field"
const val SY_CONVERSATION_COMPOSER_TAG = "ds-conversation-composer"

object SyComposerMetrics {
    val actionTouchTarget = SyMetric.touch
    val actionVisualSize = 32.dp
    val primaryActionVisualSize = 34.dp
    val actionIconSize = 18.dp
}

/** Wallpaper analysis stays owned by the host; the new library owns bubble drawing. */
@Composable
fun SyUserBubble(text: String, modifier: Modifier = Modifier) {
    val p = LocalSyColors.current
    val wallpaper = LocalAppBackgroundState.current
    val fill = wallpaper.surfaceColor(p.selected, BackgroundRegion.MIDDLE, .76f, .96f)
    val edge = if (wallpaper.hasImage) {
        if (wallpaper.darkTheme) Color.White.copy(alpha = .28f) else Color.Black.copy(alpha = .24f)
    } else p.border
    com.shenyan.designsystem.components.SyUserBubble(text, modifier, fill, edge, DsType.chatBody.withReadingWeight())
}

/**
 * Real stateful composer input; V5 colours and touch sizing, but no synthetic chat logic.
 * Historical test tag retained so existing IME/scroll tests remain meaningful.
 */
@Composable
fun SyComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = 6,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onFocusedChange: (Boolean) -> Unit = {},
) {
    val p = LocalSyColors.current
    BasicTextField(
        value = value, onValueChange = onValueChange,
        modifier = modifier.testTag("ds-composer-field")
            .heightIn(min = SyMetric.touch)
            .onFocusChanged { onFocusedChange(it.isFocused) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        enabled = enabled, visualTransformation = visualTransformation,
        maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = if (enabled) p.text else p.textTertiary,
        ),
        cursorBrush = SolidColor(p.primary),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium,
                        color = p.textTertiary)
                }
                field()
            }
        },
    )
}

/** Geometry and real callbacks for both Chat and Work composers. */
@Composable
fun SyConversationComposer(
    modifier: Modifier = Modifier,
    surfaceColor: Color = LocalSyColors.current.materialL3,
    shadowElevation: Dp = 0.dp,
    animateSize: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalSyColors.current
    val reduceMotion = LocalSyReduceMotion.current
    val shape = RoundedCornerShape(SyMetric.radiusCard)
    SyMaterialSurface(
        tier = SyMaterialTier.L2_BAR,
        modifier = modifier.testTag("ds-conversation-composer")
            .fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
            .then(if (animateSize)
                Modifier.animateContentSize(tween(SyMotion.duration(
                    SyMotion.toolExpandMs, reduceMotion, LocalSyMotionTempo.current
                ), easing = SyMotion.ease))
                else Modifier),
        shape = shape,
        tint = surfaceColor.copy(alpha = surfaceColor.alpha.coerceAtMost(.06f)),
        border = BorderStroke(1.dp, p.border),
        shadowElevation = shadowElevation,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            content = content,
        )
    }
}

/** Action wheel: a 32dp glyph plate inside the 48dp accessibility target. */
@Composable
fun SyComposerAction(
    icon: ImageVector?,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalSyColors.current.textSecondary,
    containerColor: Color = Color.Transparent,
    visualSize: Dp = SyComposerMetrics.actionVisualSize,
    iconSize: Dp = SyComposerMetrics.actionIconSize,
    content: (@Composable () -> Unit)? = null,
    iconPainter: Painter? = null,
    disabledTint: Color? = null,
) {
    val p = LocalSyColors.current
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = modifier.size(SyMetric.touch)
            .semantics { this.contentDescription = contentDescription },
        color = Color.Transparent,
        shape = CircleShape,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(visualSize).background(
                    if (enabled) containerColor else containerColor.copy(alpha = .38f),
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    content != null -> content()
                    iconPainter != null -> Icon(iconPainter, contentDescription = null,
                        tint = if (enabled) tint else (disabledTint ?: p.textTertiary),
                        modifier = Modifier.size(iconSize))
                    icon != null -> Icon(icon, contentDescription = null,
                        tint = if (enabled) tint else (disabledTint ?: p.textTertiary),
                        modifier = Modifier.size(iconSize))
                }
            }
        }
    }
}

/** V5 hierarchy transition preserves each page's real saveable UI state. */
@Composable
fun <T> SyHierarchyPage(
    page: T,
    pageKey: (T) -> String,
    depth: (T) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.(T) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    val reduceMotion = LocalSyReduceMotion.current
    val ms = SyMotion.duration(SyMotion.pushMs, reduceMotion, LocalSyMotionTempo.current)
    val shortMs = SyMotion.duration(SyMotion.pagePeerMs, reduceMotion, LocalSyMotionTempo.current)
    val enterMs = SyMotion.duration(SyMotion.pageEnterFadeMs, reduceMotion, LocalSyMotionTempo.current)
    val exitMs = SyMotion.duration(SyMotion.pageExitFadeMs, reduceMotion, LocalSyMotionTempo.current)
    AnimatedContent(
        targetState = page,
        modifier = modifier,
        contentKey = pageKey,
        transitionSpec = {
            val direction = when {
                depth(targetState) > depth(initialState) -> 1
                depth(targetState) < depth(initialState) -> -1
                else -> 0
            }
            // Use the same focus-and-fade entrance as Models across settings and features.
            val enter = scaleIn(
                initialScale = if (reduceMotion) 1f else .99f,
                animationSpec = tween(ms),
            ) + fadeIn(tween(enterMs))
            (enter togetherWith
                (slideOutHorizontally(tween(shortMs)) { -10 * direction } + fadeOut(tween(exitMs))))
                .using(SizeTransform(clip = false))
        },
        label = "syHierarchyPage",
    ) { shown ->
        holder.SaveableStateProvider(pageKey(shown)) { content(shown) }
    }
}
