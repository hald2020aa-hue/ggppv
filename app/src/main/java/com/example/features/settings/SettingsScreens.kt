package com.example.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.storage.StorageStats
import com.example.core.storage.UserSettings
import com.example.domain.models.AppThemeStyle
import com.example.domain.models.ReaderMode
import com.example.ui.theme.StorageGreen
import com.example.ui.theme.StoragePurple

/**
 * Section 16 & Video 1 (01:08 - 01:37) & Video 2 (03:21 - 03:52):
 * Complete Settings Hub & Sub-screens:
 * 1. عام (General)
 * 2. المظهر (Appearance with Theme Preview Cards, Pure Dark Mode, RTL Menus, Hide Toolbar)
 * 3. فئات المفضلة (Favorite Categories)
 * 4. التخزين (Storage Stats Bar, Save Path, Clear Cache)
 * 5. خيارات القراءة (Reading Options: Mode, Scroll Direction, Volume Keys, Webtoon, Zoom, Brightness)
 * 6. الفصول جديدة (New Chapters Notification Settings)
 * 7. إحصائيات (Statistics)
 * 8. المزيد / اعدادات اخرى (Backup & Restore, Max Concurrent Downloads, Wi-Fi Only, About)
 */
@Composable
fun SettingsMainScreen(
    onNavigateBack: () -> Unit,
    onOpenSubSection: (String) -> Unit,
    onShowToast: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "الاعدادات", onNavigateBack = onNavigateBack)

        SettingsNavItem(
            title = "عام",
            icon = Icons.Default.Home,
            testTag = "settings_general",
            onClick = { onOpenSubSection("general") }
        )
        SettingsNavItem(
            title = "المظهر",
            icon = Icons.Default.Palette,
            testTag = "settings_appearance",
            onClick = { onOpenSubSection("appearance") }
        )
        SettingsNavItem(
            title = "فئات المفضلة",
            icon = Icons.AutoMirrored.Filled.Label,
            testTag = "settings_categories",
            onClick = { onOpenSubSection("categories") }
        )
        SettingsNavItem(
            title = "التخزين",
            icon = Icons.Default.Storage,
            testTag = "settings_storage",
            onClick = { onOpenSubSection("storage") }
        )
        SettingsNavItem(
            title = "خيارات القراءة",
            icon = Icons.Default.MenuBook,
            testTag = "settings_reading",
            onClick = { onOpenSubSection("reading") }
        )
        SettingsNavItem(
            title = "الفصول جديدة",
            icon = Icons.Default.Notifications,
            testTag = "settings_new_chapters",
            onClick = { onOpenSubSection("new_chapters") }
        )
        SettingsNavItem(
            title = "إحصائيات",
            icon = Icons.Default.BarChart,
            testTag = "settings_stats",
            onClick = { onOpenSubSection("stats") }
        )
        SettingsNavItem(
            title = "المزيد",
            icon = Icons.Default.MoreHoriz,
            testTag = "settings_more",
            onClick = { onOpenSubSection("more") }
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Bottom Premium Status Card matching Video 1 (01:10)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "العضوية المميزة",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "بدون إعلانات • مفعلة بالكامل",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Button(
                    onClick = { onShowToast("جميع الميزات الاحترافية مفعلة مجاناً!") },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD4AC0D),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("مفعل", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun SettingsSubScreen(
    section: String,
    settings: UserSettings,
    storageStats: StorageStats,
    totalReadChaptersCount: Int,
    onNavigateBack: () -> Unit,
    onSelectTheme: (AppThemeStyle) -> Unit,
    onTogglePureDark: (Boolean) -> Unit,
    onSelectReaderMode: (ReaderMode) -> Unit,
    onUpdateBoolean: (String, Boolean) -> Unit,
    onUpdateBrightness: (Int) -> Unit,
    onUpdateMaxDownloads: (Int) -> Unit,
    onAddCategory: (String) -> Unit,
    onClearCache: () -> Unit,
    onClearHistory: () -> Unit,
    onShowToast: (String) -> Unit
) {
    when (section) {
        "general" -> GeneralSettingsContent(settings, onNavigateBack, onUpdateBoolean)
        "appearance" -> AppearanceSettingsContent(
            settings = settings,
            onNavigateBack = onNavigateBack,
            onSelectTheme = onSelectTheme,
            onTogglePureDark = onTogglePureDark,
            onUpdateBoolean = onUpdateBoolean
        )
        "categories" -> FavoriteCategoriesSettingsContent(
            categoriesCsv = settings.favoriteCategoriesCsv,
            onNavigateBack = onNavigateBack,
            onAddCategory = onAddCategory
        )
        "storage" -> StorageSettingsContent(
            stats = storageStats,
            onNavigateBack = onNavigateBack,
            onClearCache = onClearCache,
            onClearHistory = onClearHistory,
            onShowToast = onShowToast
        )
        "reading" -> ReadingOptionsSettingsContent(
            settings = settings,
            onNavigateBack = onNavigateBack,
            onSelectReaderMode = onSelectReaderMode,
            onUpdateBoolean = onUpdateBoolean,
            onUpdateBrightness = onUpdateBrightness
        )
        "new_chapters" -> NewChaptersSettingsContent(
            settings = settings,
            onNavigateBack = onNavigateBack,
            onUpdateBoolean = onUpdateBoolean
        )
        "stats" -> StatisticsSettingsContent(
            totalReadChaptersCount = totalReadChaptersCount,
            onNavigateBack = onNavigateBack
        )
        else -> MoreSettingsContent(
            settings = settings,
            onNavigateBack = onNavigateBack,
            onUpdateBoolean = onUpdateBoolean,
            onUpdateMaxDownloads = onUpdateMaxDownloads,
            onShowToast = onShowToast
        )
    }
}

@Composable
private fun GeneralSettingsContent(
    settings: UserSettings,
    onNavigateBack: () -> Unit,
    onUpdateBoolean: (String, Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsTopBar(title = "عام", onNavigateBack = onNavigateBack)
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = "إفتح عند البدأ",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = settings.startDestination,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "خيارات التوصيات",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            SettingsSwitchRow(
                title = "إشعارات التوصيات",
                checked = settings.recommendationNotifications,
                onCheckedChange = { onUpdateBoolean("recommendationNotifications", it) }
            )
        }
    }
}

@Composable
private fun AppearanceSettingsContent(
    settings: UserSettings,
    onNavigateBack: () -> Unit,
    onSelectTheme: (AppThemeStyle) -> Unit,
    onTogglePureDark: (Boolean) -> Unit,
    onUpdateBoolean: (String, Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "المظهر", onNavigateBack = onNavigateBack)

        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text(
                text = "السمة",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "مظهر التطبيق",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Horizontal Scrollable Phone Theme Preview Cards matching Video 1 (01:11 - 01:16) & Video 2 (03:41 - 03:44)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppThemeStyle.entries.forEach { style ->
                    val isSelected = settings.themeStyle == style
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { onSelectTheme(style) }
                            .testTag("theme_card_${style.name}")
                    ) {
                        Box(
                            modifier = Modifier
                                .width(104.dp)
                                .height(162.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(style.bgPreviewColor))
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF343946),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .padding(10.dp)
                        ) {
                            // Top pill bar inside preview card
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(22.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF2B303C))
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(Color.White),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.Black,
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }
                            }

                            // Accent pill in middle
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .width(42.dp)
                                    .height(20.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(style.primaryPreviewColor))
                            )

                            // Bottom bar inside preview card
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF232732))
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Color(style.secondaryPreviewColor))
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = style.titleAr,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "الوضع الليلي",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "اتبع مظهر النظام",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))
            SettingsSwitchRow(
                title = "الوضع الداكن النقي",
                checked = settings.pureDarkMode,
                onCheckedChange = onTogglePureDark
            )

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "تخصيص القوائم",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            SettingsSwitchRow(
                title = "ضبط القوائم من اليمين لليسار",
                checked = settings.rtlMenus,
                onCheckedChange = { onUpdateBoolean("rtlMenus", it) }
            )
            SettingsSwitchRow(
                title = "إخفاء شريط الأدوات عند التمرير",
                checked = settings.hideToolbarOnScroll,
                onCheckedChange = { onUpdateBoolean("hideToolbarOnScroll", it) }
            )
            SettingsSwitchRow(
                title = "عرض زر متابعة القراءة",
                checked = settings.showContinueReadingButton,
                onCheckedChange = { onUpdateBoolean("showContinueReadingButton", it) }
            )
            SettingsSwitchRow(
                title = "عرض أيقونات المواقع",
                checked = settings.showSourceIcons,
                onCheckedChange = { onUpdateBoolean("showSourceIcons", it) }
            )
        }
    }
}

@Composable
private fun FavoriteCategoriesSettingsContent(
    categoriesCsv: String,
    onNavigateBack: () -> Unit,
    onAddCategory: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    val categories = remember(categoriesCsv) {
        categoriesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "الكل" }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "فئات المفضلة", style = MaterialTheme.typography.headlineMedium)
            }
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "إضافة فئة")
            }
        }

        if (categories.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("لا يوجد اي فئات", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                categories.forEach { cat ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = cat,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("إضافة فئة جديدة") },
            text = {
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    label = { Text("اسم الفئة") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newCategoryName.isNotBlank()) {
                        onAddCategory(newCategoryName)
                        newCategoryName = ""
                    }
                    showAddDialog = false
                }) {
                    Text("إضافة")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
private fun StorageSettingsContent(
    stats: StorageStats,
    onNavigateBack: () -> Unit,
    onClearCache: () -> Unit,
    onClearHistory: () -> Unit,
    onShowToast: (String) -> Unit
) {
    // Matches Video 1 (01:20) & Video 2 (03:36): Multi-colored Storage Bar + Legend + Path + Clear Cache
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "التخزين", onNavigateBack = onNavigateBack)

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = "إحصائيات التخزين",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Segmented Storage Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF282D3A))
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.08f)
                        .fillMaxHeight()
                        .background(StorageGreen)
                )
                Box(
                    modifier = Modifier
                        .weight(0.06f)
                        .fillMaxHeight()
                        .background(StoragePurple)
                )
                Spacer(modifier = Modifier.weight(0.86f))
            }

            Spacer(modifier = Modifier.height(16.dp))
            StorageLegendRow(color = StorageGreen, label = "التنزيلات - ${stats.formatSize(stats.downloadsBytes)}")
            StorageLegendRow(color = Color(0xFF81C784), label = "الصفحات - ${stats.formatSize(stats.pagesCacheBytes)}")
            StorageLegendRow(color = StoragePurple, label = "آخر - ${stats.formatSize(stats.otherAppBytes)}")
            StorageLegendRow(color = Color(0xFF3A4050), label = "مساحة متوفرة - ${stats.formatSize(stats.availableBytes)}")

            HorizontalDivider(modifier = Modifier.padding(vertical = 18.dp))

            Text(
                text = "مسار حفظ التخزين",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stats.storagePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "للأعمال الجديدة؛ تبقى الأعمال المحفوظة في مجلداتها الحالية.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "نقل التخزين",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowToast("جميع الملفات محفوظة في المسار المحلي الآمن") }
                    .padding(vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClearCache() }
                    .padding(vertical = 8.dp)
            ) {
                Text(text = "مسح ذاكرة التخزين المؤقته", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "حجم ذاكرة التخزين المؤقته: ${stats.formatSize(stats.pagesCacheBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClearHistory() }
                    .padding(vertical = 8.dp)
            ) {
                Text(text = "مسح البحث السابق", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "العناصر: 0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StorageLegendRow(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReadingOptionsSettingsContent(
    settings: UserSettings,
    onNavigateBack: () -> Unit,
    onSelectReaderMode: (ReaderMode) -> Unit,
    onUpdateBoolean: (String, Boolean) -> Unit,
    onUpdateBrightness: (Int) -> Unit
) {
    // Matches Video 1 (01:24 - 01:26) & Video 2 (03:30 - 03:32)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "خيارات القراءة", onNavigateBack = onNavigateBack)

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(
                text = "الأوضاع",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val modes = ReaderMode.entries
                        val next = modes[(modes.indexOf(settings.readerMode) + 1) % modes.size]
                        onSelectReaderMode(next)
                    }
                    .padding(vertical = 8.dp)
            ) {
                Text(text = "الوضع", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = settings.readerMode.labelAr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "وضع حجم الصفحة", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "تناسب الشاشة",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

            Text(
                text = "التمرير",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "إتجاه التمرير", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (settings.readerMode == ReaderMode.HORIZONTAL_LTR) "من اليسار لليمين" else "من اليمين لليسار / رأسي",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SettingsSwitchRow(
                title = "التمرير بواسطة أزرار الصوت",
                checked = settings.scrollByVolumeKeys,
                onCheckedChange = { onUpdateBoolean("scrollByVolumeKeys", it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

            Text(
                text = "وضع الويبتون",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            SettingsSwitchRow(
                title = "تفعيل الوضع دائما",
                checked = settings.alwaysWebtoonMode,
                onCheckedChange = { onUpdateBoolean("alwaysWebtoonMode", it) }
            )
            SettingsSwitchRow(
                title = "اكتشاف تلقائي",
                checked = settings.autoDetectWebtoon,
                onCheckedChange = { onUpdateBoolean("autoDetectWebtoon", it) }
            )
            SettingsSwitchRow(
                title = "وضع التكبير",
                checked = settings.enableZoom,
                onCheckedChange = { onUpdateBoolean("enableZoom", it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

            Text(
                text = "الإعدادات العامة",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            SettingsSwitchRow(
                title = "فتح القائمة من خلال النقر على الشاشة",
                checked = settings.tapToOpenMenu,
                onCheckedChange = { onUpdateBoolean("tapToOpenMenu", it) }
            )
            SettingsSwitchRow(
                title = "عرض قائمة البطارية والتوقيت",
                checked = settings.showBatteryAndTime,
                onCheckedChange = { onUpdateBoolean("showBatteryAndTime", it) }
            )
            SettingsSwitchRow(
                title = "عرض رقم الصفحة",
                checked = settings.showPageNumber,
                onCheckedChange = { onUpdateBoolean("showPageNumber", it) }
            )
            SettingsSwitchRow(
                title = "عرض التعليقات في نهاية الفصل",
                subtitle = "يظهر ملخصاً صغيراً للتعليقات وزر فتحها بعد آخر صفحة من كل فصل.",
                checked = settings.showCommentsAtEnd,
                onCheckedChange = { onUpdateBoolean("showCommentsAtEnd", it) }
            )
            SettingsSwitchRow(
                title = "إخفاء زر القائمة",
                subtitle = "سيصبح غير مرئي، لكن لا يزال قابل للنقر",
                checked = settings.hideMenuButton,
                onCheckedChange = { onUpdateBoolean("hideMenuButton", it) }
            )
            SettingsSwitchRow(
                title = "ضبط السطوع (${settings.customBrightnessValue})",
                checked = settings.customBrightnessEnabled,
                onCheckedChange = { onUpdateBoolean("customBrightnessEnabled", it) }
            )
            Slider(
                value = settings.customBrightnessValue.toFloat(),
                onValueChange = { onUpdateBrightness(it.toInt()) },
                valueRange = 5f..100f
            )
            SettingsSwitchRow(
                title = "الحفاظ على الشاشة مفتوحة",
                checked = settings.keepScreenOn,
                onCheckedChange = { onUpdateBoolean("keepScreenOn", it) }
            )
        }
    }
}

@Composable
private fun NewChaptersSettingsContent(
    settings: UserSettings,
    onNavigateBack: () -> Unit,
    onUpdateBoolean: (String, Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsTopBar(title = "الفصول جديدة", onNavigateBack = onNavigateBack)
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            SettingsSwitchRow(
                title = "التحقق من فصول جديدة",
                checked = settings.checkNewChapters,
                onCheckedChange = { onUpdateBoolean("checkNewChapters", it) }
            )
            SettingsSwitchRow(
                title = "عبر واي فاي فقط",
                checked = settings.checkNewChaptersWifiOnly,
                onCheckedChange = { onUpdateBoolean("checkNewChaptersWifiOnly", it) }
            )
        }
    }
}

@Composable
private fun StatisticsSettingsContent(
    totalReadChaptersCount: Int,
    onNavigateBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsTopBar(title = "إحصائيات", onNavigateBack = onNavigateBack)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (totalReadChaptersCount == 0) "أقل من دقيقة" else "${totalReadChaptersCount * 4} دقيقة قراءة",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = if (totalReadChaptersCount == 0) "لا يوجد أي احصائيات.." else "عدد الأعمال المقروءة: $totalReadChaptersCount",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MoreSettingsContent(
    settings: UserSettings,
    onNavigateBack: () -> Unit,
    onUpdateBoolean: (String, Boolean) -> Unit,
    onUpdateMaxDownloads: (Int) -> Unit,
    onShowToast: (String) -> Unit
) {
    // Matches Video 1 (01:32 - 01:34) & Video 2 (03:49 - 03:51): "اعدادات اخرى"
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "اعدادات اخرى", onNavigateBack = onNavigateBack)

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = "البيانات",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowToast("تم حفظ نسخة احتياطية من قاعدة البيانات محلياً") }
                    .padding(vertical = 8.dp)
            ) {
                Text(text = "النسخ والاستعادة", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "الجهاز أو الحساب",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

            Text(
                text = "شبكة الاتصال",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val next = if (settings.maxConcurrentDownloads >= 6) 2 else settings.maxConcurrentDownloads + 1
                        onUpdateMaxDownloads(next)
                    }
                    .padding(vertical = 8.dp)
            ) {
                Text(text = "الحد الاقصى للتنزيلات", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${settings.maxConcurrentDownloads}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsSwitchRow(
                title = "التحميل فقط من خلال الواي فاي",
                checked = settings.downloadWifiOnly,
                onCheckedChange = { onUpdateBoolean("downloadWifiOnly", it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

            Text(
                text = "المساعدة",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "الابلاغ عن مشكلة في التطبيق", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "إرسال سجل الاخطاء إلى المطور (خالي من البيانات الحساسة)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "شروط الاستخدام", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "القواعد التي تنظم استخدام التطبيق والخدمات الاختيارية",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "سياسة الخصوصية", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "البيانات التي يعالجها التطبيق وخيارات التحكم والحذف",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "حول", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "الإصدار: 2.0.7.6.11",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsTopBar(
    title: String,
    onNavigateBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onNavigateBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun SettingsNavItem(
    title: String,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
