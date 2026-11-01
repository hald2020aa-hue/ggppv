package com.example.features.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import com.example.core.images.MangaImageRequestBuilder
import com.example.core.storage.UserSettings
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaPage
import com.example.domain.models.ReaderMode
import com.example.features.ReaderUiState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

/**
 * Native Jetpack Compose Reader supporting:
 * - Seamless Vertical Webtoon (LazyColumn with zero blank gaps and natural image aspect ratio)
 * - Horizontal Pager (RTL/LTR)
 * - Pinch & Double-tap Zoom
 * - Bounded Preloading of the next 2 pages only (prevents memory spikes while keeping scroll smooth)
 * - Exact restoration of both pageIndex and scrollOffset
 * - Enhanced HUD Controls with Page Slider, Next/Previous Chapter buttons, and per-page retry
 */
@Composable
fun MangaReaderScreen(
    mangaId: String,
    mangaTitle: String,
    chapterId: String,
    chapters: List<MangaChapter>,
    readerState: ReaderUiState,
    settings: UserSettings,
    isFavorite: Boolean,
    onNavigateBack: () -> Unit,
    onPageChanged: (Int, Int) -> Unit,
    onToggleControls: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleBookmark: () -> Unit,
    onSelectChapter: (String) -> Unit,
    onChangeReaderMode: (ReaderMode) -> Unit,
    onOpenReadingSettings: () -> Unit,
    onRetryFailedPage: (Int) -> Unit,
    onSaveCurrentPage: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = readerState.currentPageIndex,
        initialFirstVisibleItemScrollOffset = readerState.currentScrollOffset
    )
    val pagerState = rememberPagerState(
        initialPage = readerState.currentPageIndex,
        pageCount = { readerState.pages.size.coerceAtLeast(1) }
    )

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    var showChapterListDialog by remember { mutableStateOf(false) }
    var showBottomSettingsPopup by remember { mutableStateOf(false) }
    var showCommentsDialog by remember { mutableStateOf(false) }

    val orderedChapters = remember(chapters) {
        chapters.sortedBy { it.chapterNumber ?: 0f }
    }
    val currentChapterIndex = remember(orderedChapters, chapterId) {
        orderedChapters.indexOfFirst { it.id == chapterId }
    }
    val currentChapter = orderedChapters.getOrNull(currentChapterIndex)
    val prevChapter = orderedChapters.getOrNull(currentChapterIndex - 1)
    val nextChapter = orderedChapters.getOrNull(currentChapterIndex + 1)

    // Restore exact initial scroll position once pages finish loading
    LaunchedEffect(readerState.isLoading, chapterId) {
        if (!readerState.isLoading && readerState.pages.isNotEmpty()) {
            val targetIdx = readerState.currentPageIndex.coerceIn(0, readerState.pages.size - 1)
            if (settings.readerMode == ReaderMode.VERTICAL_WEBTOON) {
                listState.scrollToItem(targetIdx, readerState.currentScrollOffset)
            } else {
                pagerState.scrollToPage(targetIdx)
            }
        }
    }

    // Track scroll position in Vertical Webtoon mode (both item index and pixel offset)
    LaunchedEffect(listState, readerState.isLoading, settings.readerMode) {
        if (!readerState.isLoading && settings.readerMode == ReaderMode.VERTICAL_WEBTOON) {
            snapshotFlow {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }
                .distinctUntilChanged()
                .collect { (idx, offset) ->
                    if (readerState.pages.isNotEmpty()) {
                        val safeIdx = idx.coerceIn(0, readerState.pages.size - 1)
                        onPageChanged(safeIdx, offset)
                    }
                }
        }
    }

    LaunchedEffect(pagerState.currentPage, readerState.isLoading, settings.readerMode) {
        if (!readerState.isLoading && settings.readerMode != ReaderMode.VERTICAL_WEBTOON) {
            onPageChanged(pagerState.currentPage, 0)
        }
    }

    // Bounded Preloading: Preload ONLY the next 2 pages ahead of currentPageIndex
    LaunchedEffect(readerState.currentPageIndex, readerState.pages) {
        if (readerState.pages.isNotEmpty()) {
            val start = (readerState.currentPageIndex + 1).coerceAtMost(readerState.pages.size)
            val end = (readerState.currentPageIndex + 3).coerceAtMost(readerState.pages.size)
            for (i in start until end) {
                val p = readerState.pages[i]
                val data: Any = if (!p.localPath.isNullOrBlank() && File(p.localPath).exists()) {
                    File(p.localPath)
                } else {
                    p.imageUrl
                }
                context.imageLoader.enqueue(
                    MangaImageRequestBuilder.build(
                        context = context,
                        data = data,
                        crossfade = false
                    )
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1015))
            .pointerInput(settings.enableZoom) {
                if (settings.enableZoom) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (zoomScale * zoom).coerceIn(1f, 3f)
                        zoomScale = newScale
                        if (newScale > 1.05f) {
                            panX = (panX + pan.x).coerceIn(-600f, 600f)
                            panY = (panY + pan.y).coerceIn(-800f, 800f)
                        } else {
                            panX = 0f
                            panY = 0f
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (settings.enableZoom) {
                            if (zoomScale > 1.1f) {
                                zoomScale = 1f
                                panX = 0f
                                panY = 0f
                            } else {
                                zoomScale = 2f
                            }
                        }
                    },
                    onTap = { onToggleControls() }
                )
            }
    ) {
        when {
            readerState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(220.dp)
                    ) {
                        Text(
                            text = "التحميل: ${readerState.loadingProgress}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { readerState.loadingProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            readerState.pages.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "خطأ في التحميل",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "حاول مجددا!",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { onSelectChapter(chapterId) }
                        )
                    }
                }
            }
            settings.readerMode == ReaderMode.VERTICAL_WEBTOON -> {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = zoomScale,
                            scaleY = zoomScale,
                            translationX = panX,
                            translationY = panY
                        )
                        .testTag("reader_vertical_list")
                ) {
                    itemsIndexed(
                        items = readerState.pages,
                        key = { _, p -> p.index },
                        contentType = { _, _ -> "webtoon_page" }
                    ) { idx, page ->
                        val isFailed = readerState.failedPageIndices.contains(idx)
                        ReaderPageItem(
                            page = page,
                            isFailed = isFailed,
                            onRetry = { onRetryFailedPage(idx) }
                        )
                    }

                    // End of Chapter Summary + Comments Preview + Next/Previous Chapter Navigation
                    item(key = "chapter_footer") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF12151E))
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "نهاية الفصل ${currentChapter?.title ?: (currentChapterIndex + 1).coerceAtLeast(1)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(14.dp))

                            if (settings.showCommentsAtEnd) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C202B)),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "تلميذة جبل هوا: بداية تجنن وفصل أسطوري 🔥",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = { showCommentsDialog = true },
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Text("١٥ تعليق >")
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (prevChapter != null) {
                                    OutlinedButton(
                                        onClick = { onSelectChapter(prevChapter.id) },
                                        shape = RoundedCornerShape(22.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Text("الفصل السابق (${prevChapter.title})")
                                    }
                                }
                                if (nextChapter != null) {
                                    Button(
                                        onClick = { onSelectChapter(nextChapter.id) },
                                        shape = RoundedCornerShape(22.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("next_chapter_end_button")
                                    ) {
                                        Text("الفصل التالي (${nextChapter.title})")
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(64.dp))
                        }
                    }
                }
            }
            else -> {
                // Horizontal Pager Mode (RTL or LTR)
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    reverseLayout = settings.readerMode == ReaderMode.HORIZONTAL_LTR,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = zoomScale,
                            scaleY = zoomScale,
                            translationX = panX,
                            translationY = panY
                        )
                        .testTag("reader_horizontal_pager")
                ) { pageIdx ->
                    val page = readerState.pages[pageIdx]
                    val isFailed = readerState.failedPageIndices.contains(pageIdx)
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        ReaderPageItem(
                            page = page,
                            isFailed = isFailed,
                            onRetry = { onRetryFailedPage(pageIdx) }
                        )
                    }
                }
            }
        }

        // Bottom Page Counter Pill ("١/٢١")
        if (settings.showPageNumber && readerState.pages.isNotEmpty() && !readerState.isControlsVisible) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    text = "${readerState.currentPageIndex + 1} / ${readerState.pages.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        // Floating 3-dot Menu Trigger Button when HUD is hidden
        if (!readerState.isControlsVisible && !settings.hideMenuButton) {
            IconButton(
                onClick = onToggleControls,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.62f))
                    .testTag("reader_open_hud_button")
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "فتح القائمة",
                    tint = Color.White
                )
            }
        }

        // Top & Bottom Reader HUD Overlay
        AnimatedVisibility(
            visible = readerState.isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Reader Bar
                Surface(
                    color = Color(0xFF141720).copy(alpha = 0.95f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onNavigateBack,
                                modifier = Modifier.testTag("reader_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "رجوع",
                                    tint = Color.White
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = mangaTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "الفصل ${currentChapter?.title ?: "1"} • صفحة ${readerState.currentPageIndex + 1} من ${readerState.pages.size.coerceAtLeast(1)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFB5BDCB),
                                    maxLines = 1
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { showChapterListDialog = true },
                                modifier = Modifier.testTag("reader_chapter_list_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FormatListBulleted,
                                    contentDescription = "قائمة الفصول",
                                    tint = Color.White
                                )
                            }
                            IconButton(onClick = { showCommentsDialog = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Comment,
                                    contentDescription = "التعليقات",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }

                // Bottom Reader Bar with Page Indicator + Chapter Skip + Page Slider + 5 Action Icons
                Surface(
                    color = Color(0xFF141720).copy(alpha = 0.96f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        val totalPages = readerState.pages.size.coerceAtLeast(1)

                        // Page Progress Header Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "الصفحة ${readerState.currentPageIndex + 1} من $totalPages",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when (settings.readerMode) {
                                    ReaderMode.VERTICAL_WEBTOON -> "وضع الويبتون العمودي"
                                    ReaderMode.HORIZONTAL_RTL -> "صفحات (يمين إلى يسار)"
                                    ReaderMode.HORIZONTAL_LTR -> "صفحات (يسار إلى يمين)"
                                    ReaderMode.SINGLE_PAGE -> "صفحة واحدة"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Chapter Skip + Page Slider Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { nextChapter?.let { onSelectChapter(it.id) } },
                                enabled = nextChapter != null,
                                modifier = Modifier.testTag("reader_next_chapter_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "الفصل التالي",
                                    tint = if (nextChapter != null) Color.White else Color.Gray
                                )
                            }

                            Slider(
                                value = readerState.currentPageIndex.toFloat().coerceIn(0f, (totalPages - 1).coerceAtLeast(1).toFloat()),
                                onValueChange = { newVal ->
                                    val target = newVal.toInt().coerceIn(0, totalPages - 1)
                                    onPageChanged(target, 0)
                                    scope.launch {
                                        if (settings.readerMode == ReaderMode.VERTICAL_WEBTOON) {
                                            listState.scrollToItem(target, 0)
                                        } else {
                                            pagerState.scrollToPage(target)
                                        }
                                    }
                                },
                                valueRange = 0f..(totalPages - 1).coerceAtLeast(1).toFloat(),
                                steps = (totalPages - 2).coerceIn(0, 40),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 6.dp)
                                    .testTag("reader_page_slider")
                            )

                            IconButton(
                                onClick = { prevChapter?.let { onSelectChapter(it.id) } },
                                enabled = prevChapter != null,
                                modifier = Modifier.testTag("reader_prev_chapter_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "الفصل السابق",
                                    tint = if (prevChapter != null) Color.White else Color.Gray
                                )
                            }
                        }

                        // Bottom 5 Icons Row (Settings, Webtoon/Orientation, Bookmark, Save Page, Favorite)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box {
                                IconButton(onClick = { showBottomSettingsPopup = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = "إعدادات القارئ",
                                        tint = Color.White
                                    )
                                }
                                DropdownMenu(
                                    expanded = showBottomSettingsPopup,
                                    onDismissRequest = { showBottomSettingsPopup = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckBox,
                                                    contentDescription = null,
                                                    tint = if (settings.readerMode == ReaderMode.VERTICAL_WEBTOON)
                                                        MaterialTheme.colorScheme.primary else Color.Gray
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("وضع الويبتون")
                                            }
                                        },
                                        onClick = {
                                            val next = if (settings.readerMode == ReaderMode.VERTICAL_WEBTOON)
                                                ReaderMode.HORIZONTAL_RTL else ReaderMode.VERTICAL_WEBTOON
                                            onChangeReaderMode(next)
                                            showBottomSettingsPopup = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("الاعدادات") },
                                        onClick = {
                                            showBottomSettingsPopup = false
                                            onOpenReadingSettings()
                                        }
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    val modes = ReaderMode.entries
                                    val nextIdx = (modes.indexOf(settings.readerMode) + 1) % modes.size
                                    onChangeReaderMode(modes[nextIdx])
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ScreenRotation,
                                    contentDescription = "تبديل وضع القراءة",
                                    tint = Color.White
                                )
                            }

                            IconButton(onClick = onToggleBookmark) {
                                Icon(
                                    imageVector = if (readerState.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = "علامة مرجعية",
                                    tint = if (readerState.isBookmarked) MaterialTheme.colorScheme.primary else Color.White
                                )
                            }

                            IconButton(onClick = onSaveCurrentPage) {
                                Icon(
                                    imageVector = Icons.Default.SaveAlt,
                                    contentDescription = "حفظ الصفحة",
                                    tint = Color.White
                                )
                            }

                            IconButton(onClick = onToggleFavorite) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "المفضلة",
                                    tint = if (isFavorite) Color(0xFFEF5350) else Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Chapter Selector Dialog ("قائمة الفصول")
    if (showChapterListDialog) {
        AlertDialog(
            onDismissRequest = { showChapterListDialog = false },
            title = { Text("قائمة الفصول (${orderedChapters.size})") },
            text = {
                LazyColumn(modifier = Modifier.height(340.dp)) {
                    itemsIndexed(orderedChapters) { _, ch ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showChapterListDialog = false
                                    onSelectChapter(ch.id)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = ch.id == chapterId,
                                    onClick = {
                                        showChapterListDialog = false
                                        onSelectChapter(ch.id)
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "الفصل ${ch.title}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            if (ch.isDownloaded) {
                                Text(
                                    text = "محمّل",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChapterListDialog = false }) {
                    Text("إغلاق")
                }
            }
        )
    }

    if (showCommentsDialog) {
        AlertDialog(
            onDismissRequest = { showCommentsDialog = false },
            title = { Text("تعليقات الفصل") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("• خالد: فصل حماسي جداً وترجمة ممتازة!")
                    Text("• ريان: الرسم في هذا الفصل أسطوري، بانتظار الفصل القادم.")
                    Text("• سارة: شكراً على سرعة تنزيل الفصول!")
                }
            },
            confirmButton = {
                TextButton(onClick = { showCommentsDialog = false }) {
                    Text("إغلاق")
                }
            }
        )
    }
}

/**
 * Webtoon / Page Renderer:
 * - Preserves natural image aspect ratio with ContentScale.FillWidth and wrapContentHeight()
 *   so there are ZERO artificial blank gaps between vertical webtoon strips.
 * - Supports per-page retry with bounded retry key.
 */
@Composable
private fun ReaderPageItem(
    page: MangaPage,
    isFailed: Boolean,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    var retryKey by remember(page.imageUrl, page.localPath) { mutableIntStateOf(0) }

    if (isFailed) {
        ReaderPageRetryBox(
            pageNumber = page.index + 1,
            onRetry = {
                retryKey++
                onRetry()
            }
        )
    } else {
        val modelData: Any = remember(page.localPath, page.imageUrl, retryKey) {
            if (!page.localPath.isNullOrBlank() && File(page.localPath).exists()) {
                File(page.localPath)
            } else if (retryKey == 0) {
                page.imageUrl
            } else {
                val sep = if (page.imageUrl.contains("?")) "&" else "?"
                "${page.imageUrl}${sep}_r=$retryKey"
            }
        }

        val request = remember(modelData) {
            MangaImageRequestBuilder.build(
                context = context,
                data = modelData,
                crossfade = false
            )
        }

        SubcomposeAsyncImage(
            model = request,
            contentDescription = "صفحة ${page.index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            loading = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 260.dp)
                        .background(Color(0xFF10131C)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${page.index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF7E879B)
                        )
                    }
                }
            },
            error = {
                ReaderPageRetryBox(
                    pageNumber = page.index + 1,
                    onRetry = {
                        retryKey++
                        onRetry()
                    }
                )
            }
        )
    }
}

@Composable
private fun ReaderPageRetryBox(
    pageNumber: Int,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .background(Color(0xFF12151E)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "تعذّر تحميل الصفحة $pageNumber",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("حاول مجدداً")
            }
        }
    }
}
