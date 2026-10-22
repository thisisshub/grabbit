package dev.usenetstream.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.usenetstream.Tab

/** Space tab content leaves at the bottom so nothing hides under the floating nav bar. */
@Composable
fun navInset(): androidx.compose.ui.unit.Dp =
    androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 64.dp

/** Netflix-style bottom bar: four big targets in the thumb zone, animated selection. */
@Composable
fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val haptic = rememberHaptic()
    // Frosted bar: the page blurs behind it (see LocalHaze in MainActivity).
    Row(
        modifier
            .fillMaxWidth()
            .glass(
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                fill = LocalPalette.current.bar,
                elevation = 0.dp,
                blur = LocalHaze.current,
            )
            .navigationBarsPadding()
            .height(64.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Item(Tab.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home, selected, onSelect, haptic)
        Item(Tab.SEARCH, "Search", Icons.Filled.Search, Icons.Filled.Search, selected, onSelect, haptic)
        Item(Tab.MY_LIST, "My List", Icons.Filled.Add, Icons.Filled.Add, selected, onSelect, haptic)
        Item(Tab.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings, selected, onSelect, haptic)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Item(
    tab: Tab,
    label: String,
    on: ImageVector,
    off: ImageVector,
    selected: Tab,
    onSelect: (Tab) -> Unit,
    haptic: (Haptic) -> Unit,
) {
    val isOn = tab == selected
    val color by animateColorAsState(if (isOn) Fg else Muted, label = "nav-color")
    val lift by animateFloatAsState(if (isOn) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "nav-lift")
    Column(
        Modifier
            .weight(1f)
            .semantics { this.selected = isOn; role = Role.Tab }
            .pressable(pressedScale = 0.88f) { haptic(Haptic.TAP); onSelect(tab) }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (isOn) on else off,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(26.dp).graphicsLayer { translationY = -3f * lift; scaleX = 1f + 0.08f * lift; scaleY = 1f + 0.08f * lift },
        )
        Text(label, color = color, fontSize = 11.sp)
        Box(
            Modifier
                .padding(top = 3.dp)
                .width(18.dp * lift)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Red),
        )
    }
}
