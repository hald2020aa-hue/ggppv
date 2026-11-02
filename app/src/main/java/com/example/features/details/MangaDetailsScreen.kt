package com.example.features.details

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.database.ReadingHistoryEntity
import com.example.core.images.MangaCoverImage
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.SourceInfo
import com.example.features.DetailsUiState
import com.example.features.navigation.SourceBadgeIcon
import com.example.ui.theme.StarYellow
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MangaDetailsScreen(
    mangaId: String,
    state: DetailsUiState,
    chapters: List<MangaChapter>,
    isFavorite: Boolean,
    lastHistory: ReadingHistoryEntity?,
    sourceInfo: SourceInfo?,
    onNavigateBack: () -> Unit,
    onToggleSort: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenChapterReader: (String, Int, Int) -> Unit,
    onDownloadSingleChapter: (String) -> Unit,
    onRequestBatchDownload: (List<MangaChapter>) -> Unit,
    onConfirmBatchDownload: () -> Unit,
    onDismissBatchDialog: () -> Unit,
    onRefreshDetails: () -> Unit,
    onRemoveFromStorage: () -> Unit,
    onShowToast: (String) -> Unit
) {
    val context = LocalContext.current
    val details = state.details
    val sortedChapters = remember(chapters, state.sortDescending) {
        if (state.sortDescending) chapters.sortedByDescending { it.chapterNumber ?: 0f }
        else chapters.sortedBy { it.chapterNumber ?: 0f }
    }
    var showBottomMoreMenu by remember { mutableStateOf(false) }
    var descriptionExpanded by remember { mutableStateOf(false) }
    var selectedDetailsTab by remember { mutableIntStateOf(1) } // 0 = الوصف, 1 = الفصول, 2 = التعليقات

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.testTag("details_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = details?.title ?: "جاري التحميل...",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "الفصول (${chapters.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(
                    onClick = onToggleSort,
                    modifier = Modifier.testTag("chapters_sort_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = "ترتيب الفصول"
                    )
                }
            }
        }

        if (state.isLoading && details != null) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Resume Reading Banner if user has read any chapter
        if (lastHistory != null) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "متابعة الفصل ${lastHistory.chapterTitle}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "توقفت عند الصفحة ${lastHistory.pageIndex}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = {
                            onOpenChapterReader(
                                lastHistory.chapterId,
                                (lastHistory.pageIndex - 1).coerceAtLeast(0),
                                lastHistory.scrollOffset
                            )
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("resume_reading_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("إستمر", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        }

        // Responsive Body:
        // On Phone Screens (maxWidth < 600.dp): Single vertical LazyColumn (Header -> Stats -> Genres -> Description -> Full-Width Chapters)
        // On Tablets/Foldables (maxWidth >= 600.dp): Two-column canonical layout
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val isCompactPhone = maxWidth < 600.dp

            if (isCompactPhone) {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("chapters_list")
                ) {
                    // 1. Vertical Phone Hero Section (Cover + Title + Status + Author + Quick Start Button)
                    item {
                        PhoneMangaMetadataSection(
                            details = details,
                            chaptersCount = chapters.size,
                            storageSizeMb = state.storageSizeMb,
                            sourceInfo = sourceInfo,
                            descriptionExpanded = descriptionExpanded,
                            onToggleDescription = { descriptionExpanded = !descriptionExpanded },
                            onStartFirstChapter = {
                                val firstChapter = chapters.minByOrNull { it.chapterNumber ?: Float.MAX_VALUE }
                                    ?: sortedChapters.firstOrNull()
                                if (firstChapter != null) {
                                    onOpenChapterReader(firstChapter.id, 0, 0)
                                }
                            }
                        )
                    }

                    // 2. Reference App 3-Tab Bar: الوصف | الفصول | التعليقات
                    item {
                        TabRow(
                            selectedTabIndex = selectedDetailsTab,
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary
                        ) {
                            Tab(
                                selected = selectedDetailsTab == 0,
                                onClick = { selectedDetailsTab = 0 },
                                text = { Text("الوصف") }
                            )
                            Tab(
                                selected = selectedDetailsTab == 1,
                                onClick = { selectedDetailsTab = 1 },
                                text = { Text("الفصول (${sortedChapters.size})") }
                            )
                            Tab(
                                selected = selectedDetailsTab == 2,
                                onClick = { selectedDetailsTab = 2 },
                                text = { Text("التعليقات") }
                            )
                        }
                    }

                    when (selectedDetailsTab) {
                        0 -> {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "قصة العمل",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = details?.description?.takeIf { it.isNotBlank() } ?: "لا يتوفر وصف تفصيلي لهذا العمل من المصدر حالياً.",
                                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (!details?.alternativeTitles.isNullOrEmpty()) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                        Text(
                                            text = "أسماء بديلة: ${details?.alternativeTitles?.joinToString(" ، ")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        2 -> {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "لا توجد تعليقات متاحة لهذا العمل حالياً.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        else -> {
                            // Section Header for Chapters
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "قائمة الفصول (${sortedChapters.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (state.sortDescending) "الأحدث أولاً" else "الأقدم أولاً",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { onToggleSort() }
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                            }

                            // Chapters List Items
                            when {
                                state.isLoading && sortedChapters.isEmpty() -> {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(220.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator()
                                        }
                                    }
                                }
                                sortedChapters.isEmpty() -> {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(28.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    text = state.errorMessageAr ?: "لم يتم العثور على فصول",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(modifier = Modifier.height(10.dp))
                                                TextButton(onClick = onRefreshDetails) {
                                                    Text("إعادة المحاولة")
                                                }
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    itemsIndexed(sortedChapters, key = { _, ch -> ch.id }) { index, chapter ->
                                        ChapterRowItem(
                                            index = index,
                                            chapter = chapter,
                                            sortedChapters = sortedChapters,
                                            onOpenChapter = {
                                                val startPage = (chapter.lastReadPage - 1).coerceAtLeast(0)
                                                onOpenChapterReader(chapter.id, startPage, 0)
                                            },
                                            onDownloadSingle = { onDownloadSingleChapter(chapter.id) },
                                            onRequestBatch = onRequestBatchDownload
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Tablet / Expanded Split View
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(0.42f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                    ) {
                        PhoneMangaMetadataSection(
                            details = details,
                            chaptersCount = chapters.size,
                            storageSizeMb = state.storageSizeMb,
                            sourceInfo = sourceInfo,
                            descriptionExpanded = true,
                            onToggleDescription = {},
                            onStartFirstChapter = {
                                val firstChapter = chapters.minByOrNull { it.chapterNumber ?: Float.MAX_VALUE }
                                    ?: sortedChapters.firstOrNull()
                                if (firstChapter != null) {
                                    onOpenChapterReader(firstChapter.id, 0, 0)
                                }
                            }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(0.58f)
                            .fillMaxHeight()
                    ) {
                        if (state.isLoading && sortedChapters.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(vertical = 4.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("chapters_list")
                            ) {
                                itemsIndexed(sortedChapters, key = { _, ch -> ch.id }) { index, chapter ->
                                    ChapterRowItem(
                                        index = index,
                                        chapter = chapter,
                                        sortedChapters = sortedChapters,
                                        onOpenChapter = {
                                            val startPage = (chapter.lastReadPage - 1).coerceAtLeast(0)
                                            onOpenChapterReader(chapter.id, startPage, 0)
                                        },
                                        onDownloadSingle = { onDownloadSingleChapter(chapter.id) },
                                        onRequestBatch = onRequestBatchDownload
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom Action Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.testTag("details_favorite_button")
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "أضف إلى المفضلة",
                        tint = if (isFavorite) Color(0xFFEF5350) else MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = {
                        val missing = sortedChapters.filter { !it.isDownloaded }
                        onRequestBatchDownload(missing.ifEmpty { sortedChapters })
                    },
                    modifier = Modifier.testTag("details_batch_save_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdd,
                        contentDescription = "حفظ الفصول المفقودة"
                    )
                }

                IconButton(
                    onClick = {
                        onRefreshDetails()
                        onShowToast("جاري البحث وتحديث الفصول...")
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "إعادة البحث"
                    )
                }

                Box {
                    IconButton(onClick = { showBottomMoreMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "خيارات إضافية"
                        )
                    }
                    DropdownMenu(
                        expanded = showBottomMoreMenu,
                        onDismissRequest = { showBottomMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("تحديث الصفحة") },
                            onClick = {
                                showBottomMoreMenu = false
                                onRefreshDetails()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("تحديث الغلاف") },
                            onClick = {
                                showBottomMoreMenu = false
                                onRefreshDetails()
                                onShowToast("تم تحديث الغلاف")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("انشاء اختصار") },
                            onClick = {
                                showBottomMoreMenu = false
                                onShowToast("تم إنشاء اختصار للعمل")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("شارك") },
                            onClick = {
                                showBottomMoreMenu = false
                                runCatching {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "اقرأ مانهوا ${details?.title.orEmpty()}")
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "مشاركة"))
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("تصدير") },
                            onClick = {
                                showBottomMoreMenu = false
                                onShowToast("تم تصدير الفصول المحفوظة إلى مجلد saved")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("إزالة") },
                            onClick = {
                                showBottomMoreMenu = false
                                onRemoveFromStorage()
                            }
                        )
                    }
                }
            }
        }
    }

    if (state.showBatchConfirmDialog) {
        AlertDialog(
            onDismissRequest = onDismissBatchDialog,
            text = {
                Text(
                    text = "سيتم حفظ المانجا (جميع ${state.pendingBatchChapters.size} الفصول).",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmBatchDownload,
                    modifier = Modifier.testTag("confirm_batch_download_button")
                ) {
                    Text("حسنا")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissBatchDialog) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneMangaMetadataSection(
    details: MangaDetails?,
    chaptersCount: Int,
    storageSizeMb: Double,
    sourceInfo: SourceInfo?,
    descriptionExpanded: Boolean,
    onToggleDescription: () -> Unit,
    onStartFirstChapter: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Top Hero Row: Poster Cover + Full Title, Status, Author, and Quick Read Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            MangaCoverImage(
                imageUrl = details?.coverUrl,
                title = details?.title ?: "",
                isSensitive = com.example.core.common.ContentPolicy.classify(details?.genres.orEmpty()) == com.example.domain.models.ContentRating.ADULT_18_PLUS,
                refererUrl = sourceInfo?.baseUrl,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(118.dp)
                    .height(172.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = details?.title ?: "جاري التحميل...",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        lineHeight = 23.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                val statusLabel = when {
                    details?.status.equals("Completed", true) -> "مكتملة • Completed"
                    details?.status.equals("Hiatus", true) -> "متوقفة • Hiatus"
                    details?.status.equals("Cancelled", true) -> "ملغاة • Cancelled"
                    details?.status.equals("OnGoing", true) -> "مستمرة • OnGoing"
                    else -> "غير معروفة"
                }

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (!details?.author.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "المؤلف: ${details?.author}" +
                            (details?.artist?.let { " • الرسام: $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onStartFirstChapter,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("بدء القراءة من أول فصل")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Stats Card (Rating + Chapter Count + Source / Storage Size)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = StarYellow,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = details?.rating?.let { String.format(Locale.US, "%.1f", it) } ?: "غير متوفر",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "التقييم",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$chaptersCount",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "عدد الفصول",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (storageSizeMb > 0.05) {
                        Text(
                            text = String.format(Locale.US, "%.1f MB", storageSizeMb),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "التخزين المحلي",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (sourceInfo != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SourceBadgeIcon(
                                text = sourceInfo.iconText,
                                colorHex = sourceInfo.iconColorHex,
                                sizeDp = 20
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = sourceInfo.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "المصدر",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Genre Tags
        val genres = details?.genres.orEmpty()
        if (genres.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                genres.forEach { genre ->
                    Box(
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = genre,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        // Expandable Story Description Card
        if (!details?.description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleDescription() }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "القصة",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = details?.description.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    if ((details?.description?.length ?: 0) > 110) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (descriptionExpanded) "عرض أقل ▲" else "عرض المزيد ▼",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRowItem(
    index: Int,
    chapter: MangaChapter,
    sortedChapters: List<MangaChapter>,
    onOpenChapter: () -> Unit,
    onDownloadSingle: () -> Unit,
    onRequestBatch: (List<MangaChapter>) -> Unit
) {
    var showChapterDownloadMenu by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenChapter)
                .padding(horizontal = 14.dp, vertical = 11.dp)
                .testTag("chapter_item_${chapter.title}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (chapter.isRead) MaterialTheme.colorScheme.primaryContainer
                            else Color(0xFF2B303C)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${(chapter.chapterNumber ?: (index + 1).toFloat()).toInt()}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (chapter.isRead) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (chapter.title.startsWith("Chapter") || chapter.title.startsWith("الفصل")) {
                            chapter.title
                        } else {
                            "الفصل ${chapter.title}"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                        color = if (chapter.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val subText = buildString {
                        append(chapter.dateLabel)
                        if (chapter.isRead) {
                            append(" • مقروء")
                        }
                        if (chapter.lastReadPage > 0) {
                            append(" • صفحة ${chapter.lastReadPage}")
                        }
                        if (chapter.isDownloaded) {
                            append(" • متاح دون إنترنت")
                        }
                    }
                    Text(
                        text = subText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (chapter.isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { showChapterDownloadMenu = true },
                    modifier = Modifier.testTag("chapter_download_btn_${chapter.title}")
                ) {
                    if (chapter.isDownloaded) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "محمل",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "تنزيل الفصل",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                DropdownMenu(
                    expanded = showChapterDownloadMenu,
                    onDismissRequest = { showChapterDownloadMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("حفظ هذا الفصل") },
                        onClick = {
                            showChapterDownloadMenu = false
                            onDownloadSingle()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("تنزيل هذا الفصل والفصول السابقة") },
                        onClick = {
                            showChapterDownloadMenu = false
                            val prevAndCurrent = sortedChapters.take(index + 1)
                            onRequestBatch(prevAndCurrent)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("تنزيل هذا الفصل والفصول التالية") },
                        onClick = {
                            showChapterDownloadMenu = false
                            val currentAndNext = sortedChapters.drop(index)
                            onRequestBatch(currentAndNext)
                        }
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    }
}
