package com.yokhan.gates.shared.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.yokhanTypography

@Immutable
data class GatesNavigationItem(val key: String, val title: String, val glyph: String)

// Lifted from the Android shell: both adapters now share this geometry.
object GatesNavigationTokens {
    val Height = 74.dp
    val RailHeight = 58.dp
    val RailRadius = 26.dp
    val ItemRadius = 22.dp
}

@Composable
fun GatesBottomNavigation(
    selectedKey: String,
    items: List<GatesNavigationItem>,
    colors: YokhanPalette,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val typography = yokhanTypography()
    Box(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(
        Color.Transparent, colors.background.copy(alpha = .92f), colors.background,
    ))).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(GatesNavigationTokens.RailHeight)
            .clip(RoundedCornerShape(GatesNavigationTokens.RailRadius))
            .background(colors.background.copy(alpha = .88f))
            .border(1.dp, colors.border, RoundedCornerShape(GatesNavigationTokens.RailRadius)).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            items.forEach { item ->
                val selected = item.key == selectedKey
                val accent = if (selected) colors.accent else colors.secondary.copy(alpha = .58f)
                Column(Modifier.weight(1f).fillMaxHeight()
                    .testTag("route.nav.${item.key.lowercase()}")
                    .clip(RoundedCornerShape(GatesNavigationTokens.ItemRadius))
                    .selectable(selected, enabled, role = Role.Tab, onClick = { onSelect(item.key) })
                    .background(colors.primary.copy(alpha = if (selected) .09f else 0f))
                    .padding(horizontal = 2.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.width(if (selected) 24.dp else 14.dp).height(3.dp)
                        .clip(RoundedCornerShape(3.dp)).background(accent))
                    Text(item.glyph, color = accent, fontFamily = typography.mono,
                        fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 5.dp))
                    Text(item.title, color = if (selected) colors.primary else colors.secondary,
                        fontFamily = typography.mono, fontSize = 9.sp, lineHeight = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
