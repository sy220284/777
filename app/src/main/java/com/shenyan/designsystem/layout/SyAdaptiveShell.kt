package com.shenyan.designsystem.layout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.LocalSyReduceMotion
import com.shenyan.designsystem.foundation.SyMetric

/** Independent navigation contract; no Ds imports and no fake business state. */
data class SyPageRoute(val id: String, val title: String, val section: String)

/**
 * Scene-level shell corresponding to V5 web prototype.
 *
 * Width contract: phone<=600dp; mid viewport up to 1023dp uses an overlay drawer;
 * full navigation at >=1024dp; at >=1440dp the caller may provide a 318dp inspector.
 * Native system insets are consumed; never cover IME / status or gesture navigation.
 * Only the caller owns persistence, lifecycle, permission and actual page state.
 */
@Composable
fun SyAdaptiveShell(
    routes: List<SyPageRoute>,
    currentRouteId: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    pageTitle: String,
    pageDescription: String? = null,
    inspector: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalSyColors.current
    val reduceMotion = LocalSyReduceMotion.current
    var collapsed by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    val motion = if (reduceMotion) 80 else 280
    val eased = CubicBezierEasing(.22f, 1f, .36f, 1f)
    val chrome = if (colors.canvas == Color(0xFFEDE6DA)) Color(0xFFF6F1E9)
      else if (colors.canvas == Color(0xFF0F0F10)) Color(0xFF18181A)
      else Color(0xFF292521)
    BoxWithConstraints(modifier.fillMaxSize().background(colors.canvas).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val fullNav = maxWidth >= 1024.dp
        val wideInspector = maxWidth >= 1440.dp && inspector != null
        val navWidth = if (collapsed) 64.dp else 258.dp
        Column(Modifier.fillMaxSize()) {
            // Top bar is solid rather than another blur layer.
            Surface(color = chrome, border = BorderStroke(1.dp, colors.border.copy(alpha = .45f))) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (fullNav) collapsed = !collapsed else drawerOpen = !drawerOpen },
                        modifier = Modifier.size(SyMetric.touch).semantics { contentDescription = if (fullNav) "收起或展开侧栏" else "打开页面导航" }) {
                        Icon(Icons.Outlined.Menu, contentDescription = null, tint = colors.text)
                    }
                    Text("神言神语", fontWeight = FontWeight.SemiBold, color = colors.text)
                    Text("V5", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                    Spacer(Modifier.weight(1f))
                    Text("场景设计", color = colors.textTertiary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(Modifier.weight(1f)) {
                if (fullNav) {
                    SyNavRail(routes, currentRouteId, navWidth, collapsed) { id -> onNavigate(id) }
                }
                Column(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                        Text(pageTitle, color = colors.text, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!pageDescription.isNullOrBlank()) {
                            Text(pageDescription, color = colors.textTertiary,
                                style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                    Box(Modifier.fillMaxSize().padding(horizontal = if (fullNav) 24.dp else 12.dp, vertical = 12.dp)) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                            if (wideInspector) {
                                Surface(Modifier.width(318.dp).fillMaxHeight(), shape = RoundedCornerShape(20.dp),
                                    color = colors.materialL2, border = BorderStroke(1.dp, colors.border)) {
                                    Box(Modifier.fillMaxSize().padding(15.dp)) { inspector?.invoke() }
                                }
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = drawerOpen && !fullNav,
            enter = fadeIn(tween(motion, easing = eased)), exit = fadeOut(tween(motion, easing = eased))) {
            Box(Modifier.fillMaxSize().background(colors.overlay).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null) { drawerOpen = false }) {
                SyNavRail(routes, currentRouteId, 304.dp, false) { id ->
                    drawerOpen = false
                    onNavigate(id)
                }
                IconButton(onClick = { drawerOpen = false }, modifier = Modifier.align(Alignment.TopEnd).size(SyMetric.touch)) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭页面导航", tint = colors.text)
                }
            }
        }
    }
}

@Composable
private fun SyNavRail(
    routes: List<SyPageRoute>, selected: String, width: androidx.compose.ui.unit.Dp,
    collapsed: Boolean, onNavigate: (String) -> Unit
) {
    val c = LocalSyColors.current
    Surface(Modifier.width(width).fillMaxHeight(), color = c.materialL2,
        border = BorderStroke(1.dp, c.border.copy(alpha = .5f))) {
        LazyColumn(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(routes, key = { it.id }) { route ->
                val isSelected = route.id == selected
                val background by animateColorAsState(if (isSelected) c.materialL3 else Color.Transparent, label = "navSelection")
                Row(Modifier.fillMaxWidth().height(SyMetric.touch)
                    .clip(RoundedCornerShape(13.dp))
                    .background(background)
                    .clickable { onNavigate(route.id) }
                    .semantics { contentDescription = "${route.section} · ${route.title}" }
                    .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(route.id, color = if (isSelected) c.primaryPressed else c.textTertiary,
                        style = MaterialTheme.typography.labelSmall)
                    if (!collapsed) {
                        Spacer(Modifier.width(10.dp))
                        Text(route.title, color = c.text, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}