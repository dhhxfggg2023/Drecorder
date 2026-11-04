package dev.drecorder.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.drecorder.R

/**
 * The two pages a user moves between without going anywhere.
 *
 * Library, editor and updates are *places you go* — they open on top of one of
 * these and close again. Home and settings are where you *are*: they sit side
 * by side here so switching between them is one tap in the same place every
 * time, instead of a trip through the settings entry on the home screen.
 */
@Composable
fun DrecorderNavigationBar(
    current: NavPage,
    onSelect: (NavPage) -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = current == NavPage.Home,
            onClick = { onSelect(NavPage.Home) },
            icon = { Icon(AppIcons.home, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_home)) },
        )
        NavigationBarItem(
            selected = current == NavPage.Settings,
            onClick = { onSelect(NavPage.Settings) },
            icon = { Icon(AppIcons.settings, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_settings)) },
        )
    }
}

/** The pages the bar offers; everything else is reached from one of them. */
enum class NavPage { Home, Settings }

/** One place for the icons the bar needs, so both rows stay in step. */
private object AppIcons {
    val home: ImageVector = Icons.Filled.Home
    val settings: ImageVector = Icons.Filled.Tune
}
