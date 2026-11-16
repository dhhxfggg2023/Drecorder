package dev.drecorder.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.drecorder.R

/**
 * The two pages a user moves between without going anywhere.
 *
 * Library, editor and updates are *places you go* — they open on top of one of
 * these and close again. Home and settings are where you *are*: they sit side
 * by side here so switching between them is one tap in the same place every
 * time, instead of a trip through the settings entry on the home screen.
 *
 * The bar itself is a floating capsule rather than
 * Material's edge-to-edge NavigationBar: the selected page expands into a
 * gradient pill with its label, the other one keeps a quiet icon and caption.
 * Hierarchy comes from shape as well as colour, which is what makes the current
 * page readable for a low-vision user too.
 */
@Composable
fun DrecorderNavigationBar(
    current: NavPage,
    onSelect: (NavPage) -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        // Hollow, not a plate: an opaque capsule painted a second surface behind
        // the bar, which read as a white slab under the ovals. Only the selected
        // item carries a fill now; everything else floats on the page itself.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp),
            shape = RoundedCornerShape(percent = 50),
            color = Color.Transparent,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NavItem(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Home,
                    label = stringResource(R.string.nav_home),
                    selected = current == NavPage.Home,
                    onClick = {
                        if (current != NavPage.Home) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(NavPage.Home)
                        }
                    },
                )
                NavItem(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Tune,
                    label = stringResource(R.string.nav_settings),
                    selected = current == NavPage.Settings,
                    onClick = {
                        if (current != NavPage.Settings) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(NavPage.Settings)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val contentColor by animateColorAsState(
        targetValue = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
        label = "navContent",
    )
    val horizontal by animateDpAsState(
        targetValue = if (selected) 14.dp else 6.dp,
        label = "navPad",
    )
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(percent = 50))
            .then(
                if (selected) {
                    Modifier.background(Brush.linearGradient(listOf(scheme.primary, scheme.secondary)))
                } else {
                    Modifier.background(Color.Transparent)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = horizontal)
            .semantics {
                this.selected = selected
                role = Role.Tab
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(21.dp),
            )
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            } else {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    color = contentColor,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The pages the bar offers; everything else is reached from one of them. */
enum class NavPage { Home, Settings }
