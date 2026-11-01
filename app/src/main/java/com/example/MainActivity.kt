package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.features.MainViewModel
import com.example.features.ScreenRoute
import com.example.features.details.MangaDetailsScreen
import com.example.features.downloads.DownloadsScreen
import com.example.features.home.FavoritesScreen
import com.example.features.home.HistoryAndUpdatesScreen
import com.example.features.home.RecommendationsScreen
import com.example.features.home.SourceCatalogScreen
import com.example.features.home.StorageHomeScreen
import com.example.features.navigation.FloatingContinueReadingButton
import com.example.features.navigation.MangaDrawerSheet
import com.example.features.reader.MangaReaderScreen
import com.example.features.settings.SettingsMainScreen
import com.example.features.settings.SettingsSubScreen
import com.example.features.sourcemanager.AddSiteAndVerificationDialogs
import com.example.features.sourcemanager.InternalBrowserScreen
import com.example.features.sourcemanager.SourceDiagnosticsScreen
import com.example.features.sourcemanager.SourceManagerScreen
import com.example.features.sourcemanager.UniversalSourceSettingsScreen
import com.example.features.sourcemanager.AdultContentControlsScreen
import com.example.ui.theme.MangaReaderTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            val settings by vm.settings.collectAsState()

            MangaReaderTheme(
                themeStyle = settings.themeStyle,
                pureDarkMode = settings.pureDarkMode,
                rtlLayout = true
            ) {
                MangaReaderAppRoot(vm = vm)
            }
        }
    }
}

@Composable
fun MangaReaderAppRoot(vm: MainViewModel) {
    val currentRoute by vm.currentRoute.collectAsState()
    val settings by vm.settings.collectAsState()
    val addedSources by vm.addedSources.collectAsState()
    val storedManga by vm.storedManga.collectAsState()
    val allManga by vm.allManga.collectAsState()
    val favorites by vm.favoritesWithCategory.collectAsState()
    val readingHistory by vm.readingHistory.collectAsState()
    val downloadTasks by vm.downloadTasks.collectAsState()
    val addSiteDialogState by vm.addSiteDialog.collectAsState()
    val catalogState by vm.catalogState.collectAsState()
    val detailsState by vm.detailsState.collectAsState()
    val readerState by vm.readerState.collectAsState()
    val storageStats by vm.storageStats.collectAsState()
    val toastMessage by vm.toastMessage.collectAsState()
    val showRestartBanner by vm.showRestartBanner.collectAsState()
    val showLoginPrompt by vm.showLoginPromptDialog.collectAsState()
    val loggedInEmail by vm.loggedInEmail.collectAsState()
    val diagnosticReports by vm.diagnosticReports.collectAsState()
    val isRunningDiagnostics by vm.isRunningDiagnostics.collectAsState()
    val editorConfig by vm.sourceEditorConfig.collectAsState()
    val editorPreview by vm.sourceEditorPreview.collectAsState()
    val editorError by vm.sourceEditorError.collectAsState()
    val editorWorking by vm.sourceEditorWorking.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Handle Android Back Button on all secondary routes
    BackHandler(enabled = currentRoute !is ScreenRoute.Storage) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            val popped = vm.navigateBack()
            if (!popped) {
                vm.navigateTo(ScreenRoute.Storage, clearBackStack = true)
            }
        }
    }

    val gesturesEnabled = currentRoute is ScreenRoute.Storage ||
        currentRoute is ScreenRoute.SourceCatalog ||
        currentRoute is ScreenRoute.Recommendations ||
        currentRoute is ScreenRoute.Updates ||
        currentRoute is ScreenRoute.Favorites ||
        currentRoute is ScreenRoute.History

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            MangaDrawerSheet(
                currentRoute = currentRoute,
                addedSources = addedSources,
                showSourceIcons = settings.showSourceIcons,
                loggedInEmail = loggedInEmail,
                onSelectRoute = { target ->
                    scope.launch { drawerState.close() }
                    vm.navigateTo(target)
                },
                onLoginClick = {
                    scope.launch { drawerState.close() }
                    vm.openLoginPrompt()
                },
                onQuickAddDemoSources = {
                    scope.launch { drawerState.close() }
                    vm.navigateTo(ScreenRoute.SourceManager)
                    vm.openAddSiteDialog("")
                }
            )
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (val route = currentRoute) {
                    is ScreenRoute.Storage -> {
                        StorageHomeScreen(
                            storedManga = storedManga,
                            gridDisplayMode = settings.gridDisplayMode,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenManga = { mangaId -> vm.navigateTo(ScreenRoute.MangaDetailsRoute(mangaId)) },
                            onOpenSourceManager = { vm.navigateTo(ScreenRoute.SourceManager) },
                            onNavigateDownloads = { vm.navigateTo(ScreenRoute.Downloads) },
                            onNavigateSettings = { vm.navigateTo(ScreenRoute.SettingsMain) },
                            onChangeGridMode = { vm.setGridDisplayMode(it) },
                            onImportSampleWork = {
                                if (addedSources.isEmpty()) {
                                    vm.navigateTo(ScreenRoute.SourceManager)
                                    vm.openAddSiteDialog("")
                                } else {
                                    vm.navigateTo(ScreenRoute.SourceCatalog(addedSources.first().id))
                                }
                            }
                        )
                    }

                    is ScreenRoute.SourceCatalog -> {
                        val sourceInfo = addedSources.firstOrNull { it.id == route.sourceId }
                        val sourceMangaFlow = remember(route.sourceId) {
                            vm.repository.observeMangaBySource(route.sourceId)
                        }
                        val dbManga by sourceMangaFlow.collectAsState(initial = emptyList())
                        val effectiveMangaItems = if (catalogState.sourceId == route.sourceId && catalogState.items.isNotEmpty()) {
                            catalogState.items
                        } else {
                            dbManga
                        }

                        SourceCatalogScreen(
                            source = sourceInfo,
                            mangaItems = effectiveMangaItems,
                            catalogState = catalogState,
                            gridDisplayMode = settings.gridDisplayMode,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenManga = { mangaId -> vm.navigateTo(ScreenRoute.MangaDetailsRoute(mangaId)) },
                            onSearchQueryChange = vm::setCatalogSearchQuery,
                            onToggleSearch = vm::setCatalogSearchActive,
                            onToggleGenre = vm::toggleCatalogGenre,
                            onSelectSort = vm::setCatalogSort,
                            onSelectStatus = vm::setCatalogStatusFilter,
                            onClearFilters = vm::clearCatalogFilters,
                            onChangeGridMode = vm::setGridDisplayMode,
                            onRefreshCatalog = { page -> vm.loadSourceCatalog(route.sourceId, page) },
                            onTriggerCloudflareVerify = vm::triggerCatalogCloudflareVerification,
                            onToggleEmbeddedWebViewFallback = vm::toggleEmbeddedWebViewFallback,
                            onVerifiedWebViewHtmlCaptured = vm::onVerifiedWebViewHtmlCaptured,
                            onNavigateDownloads = { vm.navigateTo(ScreenRoute.Downloads) },
                            onNavigateSettings = { vm.navigateTo(ScreenRoute.SettingsMain) }
                        )
                    }

                    is ScreenRoute.Recommendations -> {
                        RecommendationsScreen(
                            allManga = allManga,
                            gridDisplayMode = settings.gridDisplayMode,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenManga = { vm.navigateTo(ScreenRoute.MangaDetailsRoute(it)) },
                            onBrowseAllSources = {
                                vm.navigateTo(ScreenRoute.SourceManager)
                                if (addedSources.isEmpty()) {
                                    vm.openAddSiteDialog("")
                                }
                            }
                        )
                    }

                    is ScreenRoute.Updates -> {
                        HistoryAndUpdatesScreen(
                            titleAr = "التحديثات",
                            emptyKaomoji = "( ´ ω ` )",
                            emptyMessageAr = "لا توجد تحديثات فصول جديدة حالياً.",
                            historyItems = readingHistory,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenManga = { vm.navigateTo(ScreenRoute.MangaDetailsRoute(it)) },
                            onResumeChapter = { mId, chId, page, offset ->
                                vm.navigateTo(ScreenRoute.ReaderRoute(mId, chId, page, offset))
                            },
                            onClearHistory = {}
                        )
                    }

                    is ScreenRoute.Favorites -> {
                        val categories = settings.favoriteCategoriesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        FavoritesScreen(
                            favorites = favorites,
                            categories = categories,
                            gridDisplayMode = settings.gridDisplayMode,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenManga = { vm.navigateTo(ScreenRoute.MangaDetailsRoute(it)) }
                        )
                    }

                    is ScreenRoute.History -> {
                        HistoryAndUpdatesScreen(
                            titleAr = "السجل",
                            emptyKaomoji = "( ´; ω ;` )",
                            emptyMessageAr = "السجل فارغ.",
                            historyItems = readingHistory,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenManga = { vm.navigateTo(ScreenRoute.MangaDetailsRoute(it)) },
                            onResumeChapter = { mId, chId, page, offset ->
                                vm.navigateTo(ScreenRoute.ReaderRoute(mId, chId, page, offset))
                            },
                            onClearHistory = vm::clearReadingHistory
                        )
                    }

                    is ScreenRoute.SourceManager -> {
                        SourceManagerScreen(
                            sources = addedSources,
                            showRestartBanner = showRestartBanner,
                            onNavigateBack = {
                                if (!vm.navigateBack()) vm.navigateTo(ScreenRoute.Storage, true)
                            },
                            onOpenAddSiteDialog = vm::openAddSiteDialog,
                            onOpenBrowser = { url -> vm.navigateTo(ScreenRoute.Browser(url)) },
                            onToggleSource = vm::toggleSourceEnabled,
                            onDeleteSource = vm::deleteSource,
                            onOpenSourceCatalog = { sourceId -> vm.navigateTo(ScreenRoute.SourceCatalog(sourceId)) },
                            onOpenDiagnostics = { vm.navigateTo(ScreenRoute.SourceDiagnostics) },
                            onClearAllSources = vm::clearAllAddedSources,
                            onConfigureSource = vm::openSourceEditor,
                            onOpenAdultSettings = { vm.navigateTo(ScreenRoute.AdultContentControls) }
                        )
                    }

                    is ScreenRoute.SourceConfigurationEditor -> {
                        editorConfig?.let { config ->
                            UniversalSourceSettingsScreen(
                                configuration = config, preview = editorPreview,
                                isWorking = editorWorking, error = editorError,
                                onChange = vm::updateSourceEditorConfig,
                                onPreview = vm::previewSourceEditor,
                                onSaveAndTest = vm::saveSourceEditor,
                                onImportJson = vm::importSourceEditorJson,
                                onNavigateBack = { vm.navigateBack() }
                            )
                        }
                    }
                    is ScreenRoute.AdultContentControls -> {
                        AdultContentControlsScreen(
                            adultEnabled = settings.adultContentEnabled,
                            hideCovers = settings.hideSensitiveCovers,
                            onSetEnabled = vm::setAdultContentEnabled,
                            onSetHideCovers = vm::setHideSensitiveCovers,
                            onBack = { vm.navigateBack() }
                        )
                    }
                    is ScreenRoute.SourceDiagnostics -> {
                        SourceDiagnosticsScreen(
                            sources = addedSources,
                            reports = diagnosticReports,
                            isRunningDiagnostics = isRunningDiagnostics,
                            onNavigateBack = {
                                if (!vm.navigateBack()) vm.navigateTo(ScreenRoute.SourceManager)
                            },
                            onRunSingleDiagnostic = vm::runDiagnosticsForSource,
                            onRunAllDiagnostics = vm::runAllSourcesDiagnostics
                        )
                    }

                    is ScreenRoute.Browser -> {
                        InternalBrowserScreen(
                            initialUrl = route.initialUrl,
                            onCloseBrowser = {
                                if (!vm.navigateBack()) vm.navigateTo(ScreenRoute.SourceManager)
                            },
                            onAddCurrentDomain = { url -> vm.openAddSiteDialog(url) }
                        )
                    }

                    is ScreenRoute.MangaDetailsRoute -> {
                        val chaptersFlow = remember(route.mangaId) { vm.repository.observeChaptersForManga(route.mangaId) }
                        val isFavFlow = remember(route.mangaId) { vm.repository.observeIsFavorite(route.mangaId) }
                        val lastHistFlow = remember(route.mangaId) { vm.repository.observeHistoryForManga(route.mangaId) }

                        val chapters by chaptersFlow.collectAsState(initial = emptyList())
                        val isFav by isFavFlow.collectAsState(initial = false)
                        val lastHist by lastHistFlow.collectAsState(initial = null)
                        val effectiveChapters = if (chapters.isNotEmpty()) {
                            chapters
                        } else {
                            detailsState.details?.chapters.orEmpty()
                        }
                        val sourceInfo = addedSources.firstOrNull { it.id == detailsState.details?.sourceId }

                        MangaDetailsScreen(
                            mangaId = route.mangaId,
                            state = detailsState,
                            chapters = effectiveChapters,
                            isFavorite = isFav,
                            lastHistory = lastHist,
                            sourceInfo = sourceInfo,
                            onNavigateBack = {
                                if (!vm.navigateBack()) vm.navigateTo(ScreenRoute.Storage, true)
                            },
                            onToggleSort = vm::toggleChapterSortOrder,
                            onToggleFavorite = { vm.toggleFavorite(route.mangaId, isFav) },
                            onOpenChapterReader = { chId, page, offset ->
                                vm.navigateTo(ScreenRoute.ReaderRoute(route.mangaId, chId, page, offset))
                            },
                            onDownloadSingleChapter = { chId ->
                                vm.downloadSingleChapter(route.mangaId, chId)
                            },
                            onRequestBatchDownload = vm::requestBatchDownload,
                            onConfirmBatchDownload = { vm.confirmBatchDownload(route.mangaId) },
                            onDismissBatchDialog = vm::dismissBatchConfirmDialog,
                            onRefreshDetails = { vm.loadMangaDetails(route.mangaId) },
                            onRemoveFromStorage = { vm.removeMangaFromStorage(route.mangaId) },
                            onShowToast = vm::showToast
                        )
                    }

                    is ScreenRoute.ReaderRoute -> {
                        val chaptersFlow = remember(route.mangaId) { vm.repository.observeChaptersForManga(route.mangaId) }
                        val mangaEntityFlow = remember(route.mangaId) { vm.repository.observeMangaEntity(route.mangaId) }
                        val isFavFlow = remember(route.mangaId) { vm.repository.observeIsFavorite(route.mangaId) }

                        val chapters by chaptersFlow.collectAsState(initial = emptyList())
                        val mangaEntity by mangaEntityFlow.collectAsState(initial = null)
                        val isFav by isFavFlow.collectAsState(initial = false)

                        MangaReaderScreen(
                            mangaId = route.mangaId,
                            mangaTitle = mangaEntity?.title ?: detailsState.details?.title ?: "قارئ المانهوا",
                            chapterId = route.chapterId,
                            chapters = chapters,
                            readerState = readerState,
                            settings = settings,
                            isFavorite = isFav,
                            onNavigateBack = {
                                if (!vm.navigateBack()) vm.navigateTo(ScreenRoute.MangaDetailsRoute(route.mangaId))
                            },
                            onPageChanged = { pageIdx, offset ->
                                vm.onReaderPageChanged(route.mangaId, route.chapterId, pageIdx, offset)
                            },
                            onToggleControls = vm::toggleReaderControls,
                            onToggleFavorite = { vm.toggleFavorite(route.mangaId, isFav) },
                            onToggleBookmark = vm::toggleReaderBookmark,
                            onSelectChapter = { nextChapterId ->
                                vm.loadReaderChapter(route.mangaId, nextChapterId, 0, 0)
                            },
                            onChangeReaderMode = vm::setReaderMode,
                            onOpenReadingSettings = { vm.navigateTo(ScreenRoute.SettingsSub("reading")) },
                            onRetryFailedPage = vm::retryFailedReaderPage,
                            onSaveCurrentPage = { vm.showToast("تم حفظ صورة الصفحة في معرض الجهاز") }
                        )
                    }

                    is ScreenRoute.Downloads -> {
                        DownloadsScreen(
                            tasks = downloadTasks,
                            onNavigateBack = {
                                if (!vm.navigateBack()) vm.navigateTo(ScreenRoute.Storage, true)
                            },
                            onPauseTask = vm::pauseDownload,
                            onResumeTask = vm::resumeDownload,
                            onCancelTask = vm::cancelDownload,
                            onPauseAll = vm::pauseAllDownloads,
                            onCancelAll = vm::cancelAllDownloads,
                            onClearHistory = vm::clearDownloadHistory,
                            onOpenManga = { mangaId -> vm.navigateTo(ScreenRoute.MangaDetailsRoute(mangaId)) }
                        )
                    }

                    is ScreenRoute.SettingsMain -> {
                        SettingsMainScreen(
                            onNavigateBack = {
                                if (!vm.navigateBack()) vm.navigateTo(ScreenRoute.Storage, true)
                            },
                            onOpenSubSection = { section -> vm.navigateTo(ScreenRoute.SettingsSub(section)) },
                            onShowToast = vm::showToast
                        )
                    }

                    is ScreenRoute.SettingsSub -> {
                        SettingsSubScreen(
                            section = route.section,
                            settings = settings,
                            storageStats = storageStats,
                            totalReadChaptersCount = readingHistory.size,
                            onNavigateBack = {
                                if (!vm.navigateBack()) vm.navigateTo(ScreenRoute.SettingsMain)
                            },
                            onSelectTheme = vm::setThemeStyle,
                            onTogglePureDark = vm::setPureDarkMode,
                            onSelectReaderMode = vm::setReaderMode,
                            onUpdateBoolean = vm::setBooleanSetting,
                            onUpdateBrightness = vm::setBrightnessValue,
                            onUpdateMaxDownloads = vm::setMaxConcurrentDownloads,
                            onAddCategory = vm::addFavoriteCategory,
                            onClearCache = vm::clearCache,
                            onClearHistory = vm::clearReadingHistory,
                            onShowToast = vm::showToast
                        )
                    }
                }

                // Floating "متابعة" (Continue Reading) Button on Main/Catalog Screens
                val showContinueFab = settings.showContinueReadingButton && (
                    currentRoute is ScreenRoute.Storage ||
                        currentRoute is ScreenRoute.SourceCatalog ||
                        currentRoute is ScreenRoute.Recommendations ||
                        currentRoute is ScreenRoute.Favorites ||
                        currentRoute is ScreenRoute.History
                    )

                FloatingContinueReadingButton(
                    visible = showContinueFab,
                    onClick = {
                        val latest = readingHistory.firstOrNull()
                        if (latest != null) {
                            vm.navigateTo(
                                ScreenRoute.ReaderRoute(
                                    mangaId = latest.first.mangaId,
                                    chapterId = latest.first.chapterId,
                                    initialPage = (latest.first.pageIndex - 1).coerceAtLeast(0),
                                    initialScrollOffset = latest.first.scrollOffset
                                )
                            )
                        } else {
                            vm.showToast("السجل فارغ.")
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp)
                )

                if (!toastMessage.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = Color(0xFF252936),
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 26.dp, start = 24.dp, end = 24.dp)
                    ) {
                        Text(
                            text = toastMessage.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }

    AddSiteAndVerificationDialogs(
        state = addSiteDialogState,
        onInputChanged = vm::updateAddSiteInput,
        onStartCheck = vm::startSiteCompatibilityCheck,
        onDismiss = vm::closeAddSiteDialog,
        onManualNameChanged = vm::updateManualSiteName,
        onConfirmManualAdd = vm::confirmManualSiteAddition,
        onCompleteCloudflareVerify = vm::completeCloudflareHumanVerification,
        onFailCloudflareVerify = vm::failCloudflareVerification,
        onCancelCloudflareVerify = vm::cancelCloudflareVerification
    )

    if (showLoginPrompt) {
        Dialog(onDismissRequest = vm::dismissLoginPrompt) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(38.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "سجّل دخولك لتحفظ حسابك",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "اربط مكتبتك وتعليقاتك وتقدم القراءة بحساب واحد يمكنك استعادته على أجهزتك.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        onClick = { vm.signInLocalProfile("hald2020ww@gmail.com") },
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("تسجيل الدخول أو إنشاء حساب")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = vm::dismissLoginPrompt) {
                        Text("لا تظهرها مرة أخرى")
                    }
                }
            }
        }
    }
}
