package com.example.features.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.models.SourceInfo
import com.example.features.ScreenRoute

@Composable
fun MangaDrawerSheet(
    currentRoute: ScreenRoute,
    addedSources: List<SourceInfo>,
    showSourceIcons: Boolean,
    loggedInEmail: String?,
    onSelectRoute: (ScreenRoute) -> Unit,
    onLoginClick: () -> Unit,
    onQuickAddDemoSources: () -> Unit
) {
    ModalDrawerSheet(
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight()
            .testTag("navigation_drawer"),
        drawerContainerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            // Top Notification Bell + User Profile Header (Matching Video 1 & 5)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLoginClick() }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = "الحساب",
                        tint = Color(0xFFB0B8C8),
                        modifier = Modifier.size(60.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (loggedInEmail != null) {
                        Text(
                            text = "خالد الصايدي",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = loggedInEmail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "انقر هنا لتسجيل الدخول",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                IconButton(onClick = onLoginClick) {
                    Icon(
                        imageVector = Icons.Default.NotificationsNone,
                        contentDescription = "الإشعارات",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )

            DrawerNavRow(
                label = "التخزين",
                icon = Icons.Default.Book,
                selected = currentRoute is ScreenRoute.Storage,
                testTag = "drawer_storage",
                onClick = { onSelectRoute(ScreenRoute.Storage) }
            )
            DrawerNavRow(
                label = "التوصيات",
                icon = Icons.Default.Lightbulb,
                selected = currentRoute is ScreenRoute.Recommendations,
                testTag = "drawer_recommendations",
                onClick = { onSelectRoute(ScreenRoute.Recommendations) }
            )
            DrawerNavRow(
                label = "التحديثات",
                icon = Icons.Default.Refresh,
                selected = currentRoute is ScreenRoute.Updates,
                testTag = "drawer_updates",
                onClick = { onSelectRoute(ScreenRoute.Updates) }
            )
            DrawerNavRow(
                label = "المفضلة",
                icon = Icons.Outlined.StarOutline,
                selected = currentRoute is ScreenRoute.Favorites,
                testTag = "drawer_favorites",
                onClick = { onSelectRoute(ScreenRoute.Favorites) }
            )
            DrawerNavRow(
                label = "السجل",
                icon = Icons.Default.History,
                selected = currentRoute is ScreenRoute.History,
                testTag = "drawer_history",
                onClick = { onSelectRoute(ScreenRoute.History) }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )

            DrawerNavRow(
                label = "إدارة المواقع",
                icon = Icons.Default.Extension,
                selected = currentRoute is ScreenRoute.SourceManager,
                testTag = "drawer_source_manager",
                onClick = { onSelectRoute(ScreenRoute.SourceManager) }
            )

            val enabledSources = addedSources.filter { it.isEnabled }
            if (enabledSources.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "المواقع المضافة",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                enabledSources.forEach { src ->
                    val isSelected = (currentRoute as? ScreenRoute.SourceCatalog)?.sourceId == src.id
                    NavigationDrawerItem(
                        label = {
                            Text(
                                text = src.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        icon = {
                            if (showSourceIcons) {
                                SourceBadgeIcon(
                                    text = src.iconText,
                                    colorHex = src.iconColorHex
                                )
                            }
                        },
                        selected = isSelected,
                        onClick = { onSelectRoute(ScreenRoute.SourceCatalog(src.id)) },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .testTag("drawer_source_${src.id}")
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "لم تضف أي مواقع بعد",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Button(
                    onClick = onQuickAddDemoSources,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag("quick_add_sources_button")
                ) {
                    Text(
                        text = "+ إضافة موقع يدوياً",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )

            DrawerNavRow(
                label = "الاعدادات",
                icon = Icons.Default.Settings,
                selected = currentRoute is ScreenRoute.SettingsMain || currentRoute is ScreenRoute.SettingsSub,
                testTag = "drawer_settings",
                onClick = { onSelectRoute(ScreenRoute.SettingsMain) }
            )
        }
    }
}

@Composable
private fun DrawerNavRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium
            )
        },
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = label
            )
        },
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedContainerColor = Color.Transparent
        ),
        modifier = Modifier
            .padding(vertical = 2.dp)
            .testTag(testTag)
    )
}

@Composable
fun SourceBadgeIcon(
    text: String,
    colorHex: Long,
    sizeDp: Int = 28
) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(colorHex)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.take(2),
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (sizeDp * 0.48f).sp
        )
    }
}

@Composable
fun FloatingContinueReadingButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF283552),
        contentColor = Color(0xFFD6E2FF),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = modifier.testTag("continue_reading_fab")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = "متابعة",
                tint = Color(0xFF96B0F8),
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "متابعة",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }
    }
}
