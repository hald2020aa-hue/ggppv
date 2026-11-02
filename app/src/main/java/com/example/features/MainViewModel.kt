package com.example.features

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.common.PipelineLogger
import com.example.core.common.PipelineStage
import com.example.core.database.MangaDatabase
import com.example.core.downloads.DownloadEngine
import com.example.core.storage.AppPreferences
import com.example.core.storage.LocalChapterStorage
import com.example.core.storage.StorageStats
import com.example.core.storage.UserSettings
import com.example.core.verification.VerificationDetector
import com.example.core.verification.VerificationManager
import com.example.core.verification.VerificationRepository
import com.example.core.verification.VerificationRequiredException
import com.example.core.verification.VerificationSession
import com.example.core.verification.VerificationSessionManager
import com.example.core.verification.VerificationState
import com.example.data.repositories.MangaRepository
import com.example.data.repositories.SourceRepository
import com.example.domain.models.AppThemeStyle
import com.example.domain.models.DownloadTask
import com.example.domain.models.GridDisplayMode
import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.example.domain.models.ReaderMode
import com.example.domain.models.SortOption
import com.example.domain.models.SourceDiagnosticReport
import com.example.domain.models.SourceInfo
import com.example.domain.models.SourceStatus
import com.example.domain.models.StatusFilter
import com.example.sourceapi.SourceConfiguration
import com.example.sourceapi.SourceManager
import com.example.sourceapi.SourceRegistry
import com.example.sourceapi.SourceValidationStepResult
import com.example.sourceapi.SourceValidator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

sealed class ScreenRoute {
    data object Storage : ScreenRoute()
    data object Recommendations : ScreenRoute()
    data object Updates : ScreenRoute()
    data object Favorites : ScreenRoute()
    data object History : ScreenRoute()
    data object SourceManager : ScreenRoute()
    data object SourceDiagnostics : ScreenRoute()
    data class SourceConfigurationEditor(val sourceId: String) : ScreenRoute()
    data object AdultContentControls : ScreenRoute()
    data class Browser(val initialUrl: String = "https://www.google.com") : ScreenRoute()
    data class SourceCatalog(val sourceId: String) : ScreenRoute()
    data class MangaDetailsRoute(val mangaId: String) : ScreenRoute()
    data class ReaderRoute(
        val mangaId: String,
        val chapterId: String,
        val initialPage: Int = 0,
        val initialScrollOffset: Int = 0
    ) : ScreenRoute()
    data object Downloads : ScreenRoute()
    data object SettingsMain : ScreenRoute()
    data class SettingsSub(val section: String) : ScreenRoute()
}

data class AddSiteDialogState(
    val isVisible: Boolean = false,
    val inputUrl: String = "",
    val isChecking: Boolean = false,
    val stepMessageAr: String = "",
    val errorMessageAr: String? = null,
    val showManualConfirmDialog: Boolean = false,
    val manualSiteName: String = "",
    val pendingValidationResult: SourceValidationStepResult? = null,
    val showCloudflareDialog: Boolean = false,
    val cloudflareDomain: String = "",
    val cloudflareSourceId: String = "",
    val cloudflareStepAr: String = "يتم التحقق الآن..."
)

data class CatalogUiState(
    val sourceId: String = "",
    val items: List<MangaSummary> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingNextPage: Boolean = false,
    val canLoadMore: Boolean = true,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val selectedGenres: Set<String> = emptySet(),
    val selectedSort: SortOption = SortOption.LATEST_ADDED,
    val selectedStatus: StatusFilter = StatusFilter.ALL,
    val currentPage: Int = 1,
    val requiresCloudflareGate: Boolean = false,
    val verificationState: VerificationState = VerificationState.NOT_REQUIRED,
    val verificationReasonAr: String? = null,
    val showEmbeddedWebViewFallback: Boolean = false,
    val errorMessageAr: String? = null
)

data class DetailsUiState(
    val isLoading: Boolean = false,
    val details: MangaDetails? = null,
    val sortDescending: Boolean = false,
    val storageSizeMb: Double = 0.0,
    val errorMessageAr: String? = null,
    val showBatchConfirmDialog: Boolean = false,
    val pendingBatchChapters: List<MangaChapter> = emptyList()
)

data class ReaderUiState(
    val isLoading: Boolean = true,
    val loadingProgress: Int = 0,
    val pages: List<MangaPage> = emptyList(),
    val currentPageIndex: Int = 0,
    val currentScrollOffset: Int = 0,
    val isControlsVisible: Boolean = false,
    val failedPageIndices: Set<Int> = emptySet(),
    val isBookmarked: Boolean = false,
    val refererUrl: String? = null,
    val errorMessageAr: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = MangaDatabase.getInstance(application)
    private val storage = LocalChapterStorage(application)
    val preferences = AppPreferences(application)

    val verificationSessionManager = VerificationSessionManager()
    val verificationRepository = VerificationRepository(
        sessionManager = verificationSessionManager,
        preferences = preferences
    )
    val verificationManager = VerificationManager(
        detector = VerificationDetector,
        sessionManager = verificationSessionManager,
        repository = verificationRepository
    )

    // Real built-in adapters always exist, while the user's saved source list remains independent.
    val registry = SourceRegistry(com.example.sources.BuiltInSourceCatalog.createAll())
    private val validator = SourceValidator(registry, verificationManager)
    val sourceManager = SourceManager(registry, validator, verificationManager)
    private val downloadEngine = DownloadEngine(application, db, storage, registry)

    val repository = MangaRepository(
        dao = db.mangaDao(),
        registry = registry,
        validator = validator,
        storage = storage,
        downloadEngine = downloadEngine,
        preferences = preferences,
        verificationManager = verificationManager,
        sourceManager = sourceManager
    )
    val sourceRepository: SourceRepository = repository
    val verificationSessions: StateFlow<Map<String, VerificationSession>> = repository.verificationSessionsFlow

    // ─── NAVIGATION STACK ───
    private val backStack = ArrayDeque<ScreenRoute>()
    private val _currentRoute = MutableStateFlow<ScreenRoute>(ScreenRoute.Storage)
    val currentRoute: StateFlow<ScreenRoute> = _currentRoute.asStateFlow()

    // ─── GLOBAL UI STATES ───
    val settings: StateFlow<UserSettings> = preferences.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserSettings()
    )

    val addedSources: StateFlow<List<SourceInfo>> = repository.addedSourcesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val storedManga: StateFlow<List<MangaSummary>> = repository.storedMangaFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allManga: StateFlow<List<MangaSummary>> = repository.allMangaFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val favoritesWithCategory = repository.favoritesWithCategoryFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val readingHistory = repository.readingHistoryWithMangaFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val downloadTasks: StateFlow<List<DownloadTask>> = repository.downloadTasksFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _showRestartBanner = MutableStateFlow(false)
    val showRestartBanner: StateFlow<Boolean> = _showRestartBanner.asStateFlow()

    private val _showLoginPromptDialog = MutableStateFlow(false)
    val showLoginPromptDialog: StateFlow<Boolean> = _showLoginPromptDialog.asStateFlow()

    private val _loggedInEmail = MutableStateFlow<String?>(null)
    val loggedInEmail: StateFlow<String?> = _loggedInEmail.asStateFlow()

    private val _addSiteDialog = MutableStateFlow(AddSiteDialogState())
    val addSiteDialog: StateFlow<AddSiteDialogState> = _addSiteDialog.asStateFlow()

    private val _catalogState = MutableStateFlow(CatalogUiState())
    val catalogState: StateFlow<CatalogUiState> = _catalogState.asStateFlow()

    private val _detailsState = MutableStateFlow(DetailsUiState())
    val detailsState: StateFlow<DetailsUiState> = _detailsState.asStateFlow()

    private val _readerState = MutableStateFlow(ReaderUiState())
    val readerState: StateFlow<ReaderUiState> = _readerState.asStateFlow()

    private val _diagnosticReports = MutableStateFlow<Map<String, SourceDiagnosticReport>>(emptyMap())
    val diagnosticReports: StateFlow<Map<String, SourceDiagnosticReport>> = _diagnosticReports.asStateFlow()

    private val _sourceEditorConfig = MutableStateFlow<SourceConfiguration?>(null)
    val sourceEditorConfig: StateFlow<SourceConfiguration?> = _sourceEditorConfig.asStateFlow()
    private val _sourceEditorPreview = MutableStateFlow<List<MangaSummary>>(emptyList())
    val sourceEditorPreview: StateFlow<List<MangaSummary>> = _sourceEditorPreview.asStateFlow()
    private val _sourceEditorError = MutableStateFlow<String?>(null)
    val sourceEditorError: StateFlow<String?> = _sourceEditorError.asStateFlow()
    private val _sourceEditorWorking = MutableStateFlow(false)
    val sourceEditorWorking: StateFlow<Boolean> = _sourceEditorWorking.asStateFlow()

    fun openSourceEditor(sourceId: String) {
        viewModelScope.launch {
            val source = addedSources.value.firstOrNull { it.id == sourceId }
                ?: return@launch
            _sourceEditorConfig.value = repository.getSourceConfiguration(sourceId)
                ?: SourceConfiguration(domain = source.domain, sourceId = source.id, name = source.name)
            _sourceEditorPreview.value = emptyList()
            _sourceEditorError.value = null
            navigateTo(ScreenRoute.SourceConfigurationEditor(sourceId))
        }
    }

    fun updateSourceEditorConfig(config: SourceConfiguration) { _sourceEditorConfig.value = config }

    fun importSourceEditorJson(json: String) {
        runCatching { SourceConfiguration.fromJson(json) }.onSuccess { imported ->
            if (imported.domain != _sourceEditorConfig.value?.domain) {
                _sourceEditorError.value = "ملف الإعدادات يخص نطاقاً مختلفاً"
            } else {
                _sourceEditorConfig.value = imported.copy(sourceId = _sourceEditorConfig.value!!.sourceId)
                _sourceEditorError.value = null
            }
        }.onFailure { _sourceEditorError.value = it.message ?: "JSON غير صالح" }
    }

    fun previewSourceEditor() {
        val config = _sourceEditorConfig.value ?: return
        viewModelScope.launch {
            _sourceEditorWorking.value = true
            _sourceEditorError.value = null
            try {
                _sourceEditorPreview.value = repository.previewSourceConfiguration(config)
            } catch (e: Exception) {
                _sourceEditorError.value = e.message ?: "تعذّرت المعاينة"
            } finally { _sourceEditorWorking.value = false }
        }
    }

    fun saveSourceEditor() {
        val config = _sourceEditorConfig.value ?: return
        viewModelScope.launch {
            _sourceEditorWorking.value = true
            _sourceEditorError.value = null
            try {
                val result = repository.saveSourceConfiguration(config)
                _diagnosticReports.value += (result.sourceId to result)
                showToast("حالة المصدر: ${result.adapterStatus.name}")
                navigateBack()
            } catch (e: Exception) {
                _sourceEditorError.value = e.message ?: "تعذّر حفظ الإعدادات"
            } finally { _sourceEditorWorking.value = false }
        }
    }

    fun setAdultContentEnabled(enabled: Boolean, acknowledged: Boolean) {
        viewModelScope.launch { preferences.setAdultContentEnabled(enabled, acknowledged) }
    }
    fun setHideSensitiveCovers(hide: Boolean) {
        viewModelScope.launch { preferences.setHideSensitiveCovers(hide) }
    }

    private val _isRunningDiagnostics = MutableStateFlow(false)
    val isRunningDiagnostics: StateFlow<Boolean> = _isRunningDiagnostics.asStateFlow()

    private val _storageStats = MutableStateFlow(
        StorageStats(
            downloadsBytes = 0L,
            pagesCacheBytes = 0L,
            otherAppBytes = 7_549_747L,
            availableBytes = 264_784_000_000L,
            storagePath = storage.getSavedRootDir().absolutePath
        )
    )
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    private var readerLoadJob: Job? = null
    private var catalogLoadJob: Job? = null
    private var catalogObserveJob: Job? = null
    private var detailsLoadJob: Job? = null

    init {
        viewModelScope.launch {
            // Never delete the sources table on startup: it also holds user-created sources.
            repository.ensureDefaultSourcesRegistered()
        }
        refreshStorageStats()
    }

    fun navigateTo(route: ScreenRoute, clearBackStack: Boolean = false) {
        if (clearBackStack) {
            backStack.clear()
        } else if (_currentRoute.value != route) {
            backStack.addLast(_currentRoute.value)
        }
        _currentRoute.value = route

        when (route) {
            is ScreenRoute.SourceCatalog -> loadSourceCatalog(route.sourceId)
            is ScreenRoute.MangaDetailsRoute -> loadMangaDetails(route.mangaId)
            is ScreenRoute.ReaderRoute -> loadReaderChapter(
                mangaId = route.mangaId,
                chapterId = route.chapterId,
                initialPage = route.initialPage,
                initialScrollOffset = route.initialScrollOffset
            )
            is ScreenRoute.SettingsSub -> if (route.section == "storage") refreshStorageStats()
            else -> {}
        }
    }

    fun navigateBack(): Boolean {
        val previous = backStack.removeLastOrNull() ?: return false
        _currentRoute.value = previous
        when (previous) {
            is ScreenRoute.MangaDetailsRoute -> loadMangaDetails(previous.mangaId)
            is ScreenRoute.SourceCatalog -> {
                if (_catalogState.value.sourceId != previous.sourceId || _catalogState.value.items.isEmpty()) {
                    loadSourceCatalog(previous.sourceId)
                }
            }
            else -> {}
        }
        return true
    }

    fun showToast(message: String) {
        viewModelScope.launch {
            _toastMessage.value = message
            delay(2800L)
            if (_toastMessage.value == message) {
                _toastMessage.value = null
            }
        }
    }

    fun dismissLoginPrompt() {
        _showLoginPromptDialog.value = false
    }

    fun openLoginPrompt() {
        _showLoginPromptDialog.value = true
    }

    fun signInLocalProfile(email: String) {
        _loggedInEmail.value = email.ifBlank { "hald2020ww@gmail.com" }
        _showLoginPromptDialog.value = false
        showToast("تم ربط الحساب محلياً بنجاح")
    }

    // ─── ADD SOURCE & COMPATIBILITY CHECK FLOW ───
    fun openAddSiteDialog(prefillUrl: String = "") {
        _addSiteDialog.value = AddSiteDialogState(
            isVisible = true,
            inputUrl = prefillUrl
        )
    }

    fun updateAddSiteInput(url: String) {
        _addSiteDialog.value = _addSiteDialog.value.copy(
            inputUrl = url,
            errorMessageAr = null
        )
    }

    fun closeAddSiteDialog() {
        _addSiteDialog.value = AddSiteDialogState()
    }

    fun startSiteCompatibilityCheck() {
        val rawUrl = _addSiteDialog.value.inputUrl.trim()
        if (rawUrl.isEmpty()) {
            _addSiteDialog.value = _addSiteDialog.value.copy(
                errorMessageAr = "يرجى إدخال عنوان نطاق الموقع أولاً (مثال: mangalik.net)"
            )
            return
        }

        viewModelScope.launch {
            _addSiteDialog.value = _addSiteDialog.value.copy(
                isChecking = true,
                errorMessageAr = null,
                stepMessageAr = "جار مراجعة حالة توافق الموقع..."
            )
            delay(350L)

            val res = repository.validateSiteDomain(rawUrl)
            if (res.isFailure) {
                _addSiteDialog.value = _addSiteDialog.value.copy(
                    isChecking = false,
                    errorMessageAr = res.exceptionOrNull()?.message ?: "رابط غير صالح"
                )
                return@launch
            }

            val stepResult = res.getOrThrow()

            if (stepResult.status == SourceStatus.REQUIRES_VERIFICATION ||
                stepResult.status == SourceStatus.NEEDS_VERIFICATION) {
                val matchedId = stepResult.matchedSource?.id.orEmpty()
                repository.startSourceVerification(matchedId, stepResult.normalizedDomain)
                _addSiteDialog.value = _addSiteDialog.value.copy(
                    isChecking = false,
                    showCloudflareDialog = true,
                    cloudflareDomain = stepResult.normalizedDomain,
                    cloudflareSourceId = matchedId,
                    cloudflareStepAr = "يتم التحقق الآن...",
                    pendingValidationResult = stepResult
                )
                return@launch
            }

            _addSiteDialog.value = _addSiteDialog.value.copy(
                stepMessageAr = "جار التحقق من توافق الموقع..."
            )
            delay(300L)

            if (stepResult.status == SourceStatus.UNSUPPORTED) {
                _addSiteDialog.value = _addSiteDialog.value.copy(
                    isChecking = false,
                    errorMessageAr = stepResult.messageAr
                )
                return@launch
            }

            _addSiteDialog.value = _addSiteDialog.value.copy(
                stepMessageAr = "جار التحقق من مطابقة الأعمال..."
            )
            delay(300L)

            _addSiteDialog.value = _addSiteDialog.value.copy(
                stepMessageAr = "جار التحقق من مطابقة الفصول..."
            )
            delay(280L)

            _addSiteDialog.value = _addSiteDialog.value.copy(
                isChecking = false,
                showManualConfirmDialog = true,
                manualSiteName = stepResult.suggestedName,
                pendingValidationResult = stepResult
            )
        }
    }

    fun completeCloudflareHumanVerification(
        verifiedHtml: String? = null,
        pageTitle: String = ""
    ) {
        val domain = _addSiteDialog.value.cloudflareDomain
        val sourceId = _addSiteDialog.value.cloudflareSourceId.ifBlank {
            (_currentRoute.value as? ScreenRoute.SourceCatalog)?.sourceId.orEmpty()
        }
        viewModelScope.launch {
            _addSiteDialog.value = _addSiteDialog.value.copy(
                cloudflareStepAr = "نجح التحقق. في انتظار $domain للاستجابة..."
            )
            delay(350L)

            val session = repository.completeSourceVerification(
                sourceId = sourceId,
                domain = domain,
                verifiedHtml = verifiedHtml,
                pageTitle = pageTitle
            )

            if (session.state != VerificationState.COMPLETED) {
                // Challenge page did not finish yet
                _addSiteDialog.value = AddSiteDialogState()
                _catalogState.value = _catalogState.value.copy(
                    requiresCloudflareGate = true,
                    verificationState = session.state,
                    verificationReasonAr = session.reasonAr
                )
                return@launch
            }

            if (!verifiedHtml.isNullOrBlank() && sourceId.isNotBlank()) {
                repository.ingestVerifiedWebViewHtml(
                    sourceId = sourceId,
                    html = verifiedHtml,
                    baseUrl = "https://$domain"
                )
            }

            val route = _currentRoute.value
            if (route is ScreenRoute.SourceCatalog) {
                _addSiteDialog.value = AddSiteDialogState()
                loadSourceCatalog(route.sourceId)
                return@launch
            }

            val revalidated = repository.validateSiteDomain(domain).getOrNull()
            _addSiteDialog.value = _addSiteDialog.value.copy(
                showCloudflareDialog = false,
                showManualConfirmDialog = true,
                manualSiteName = revalidated?.suggestedName ?: domain,
                pendingValidationResult = revalidated
            )
        }
    }

    fun cancelCloudflareVerification() {
        val dialog = _addSiteDialog.value
        val domain = dialog.cloudflareDomain
        val sourceId = dialog.cloudflareSourceId.ifBlank {
            (_currentRoute.value as? ScreenRoute.SourceCatalog)?.sourceId.orEmpty()
        }
        if (domain.isNotBlank()) {
            val cancelledSession = repository.cancelSourceVerification(sourceId, domain)
            if (_currentRoute.value is ScreenRoute.SourceCatalog) {
                _catalogState.value = _catalogState.value.copy(
                    isLoading = false,
                    requiresCloudflareGate = true,
                    verificationState = cancelledSession.state,
                    verificationReasonAr = cancelledSession.reasonAr
                )
            }
        }
        _addSiteDialog.value = AddSiteDialogState()
    }

    fun failCloudflareVerification(reasonAr: String) {
        val dialog = _addSiteDialog.value
        val domain = dialog.cloudflareDomain
        val sourceId = dialog.cloudflareSourceId.ifBlank {
            (_currentRoute.value as? ScreenRoute.SourceCatalog)?.sourceId.orEmpty()
        }
        if (domain.isNotBlank()) {
            val failedSession = repository.failSourceVerification(sourceId, domain, reasonAr)
            if (_currentRoute.value is ScreenRoute.SourceCatalog) {
                _catalogState.value = _catalogState.value.copy(
                    isLoading = false,
                    requiresCloudflareGate = true,
                    verificationState = failedSession.state,
                    verificationReasonAr = failedSession.reasonAr
                )
            }
        }
        _addSiteDialog.value = AddSiteDialogState()
    }

    fun triggerCatalogCloudflareVerification(domain: String) {
        val activeSourceId = (_currentRoute.value as? ScreenRoute.SourceCatalog)?.sourceId
            ?: _catalogState.value.sourceId
        val session = repository.startSourceVerification(activeSourceId, domain)
        _catalogState.value = _catalogState.value.copy(
            verificationState = session.state,
            verificationReasonAr = session.reasonAr
        )
        _addSiteDialog.value = AddSiteDialogState(
            isVisible = false,
            showCloudflareDialog = true,
            cloudflareDomain = domain,
            cloudflareSourceId = activeSourceId,
            cloudflareStepAr = "يتم التحقق الآن..."
        )
    }

    fun toggleEmbeddedWebViewFallback(enabled: Boolean) {
        _catalogState.value = _catalogState.value.copy(
            showEmbeddedWebViewFallback = enabled
        )
    }

    fun onVerifiedWebViewHtmlCaptured(sourceId: String, html: String, baseUrl: String) {
        viewModelScope.launch {
            val ingested = repository.ingestVerifiedWebViewHtml(sourceId, html, baseUrl)
            if (ingested.isNotEmpty() && _catalogState.value.sourceId == sourceId) {
                _catalogState.value = _catalogState.value.copy(
                    items = ingested,
                    isLoading = false,
                    requiresCloudflareGate = false,
                    showEmbeddedWebViewFallback = false,
                    verificationState = VerificationState.COMPLETED,
                    errorMessageAr = null
                )
            }
        }
    }

    fun updateManualSiteName(name: String) {
        _addSiteDialog.value = _addSiteDialog.value.copy(manualSiteName = name)
    }

    fun confirmManualSiteAddition() {
        val state = _addSiteDialog.value
        val pending = state.pendingValidationResult ?: return
        viewModelScope.launch {
            val addRes = repository.confirmAndAddSource(pending, state.manualSiteName)
            _addSiteDialog.value = AddSiteDialogState()
            if (addRes.isSuccess) {
                _showRestartBanner.value = true
                showToast("تم الإضافة بنجاح")
            } else {
                showToast(addRes.exceptionOrNull()?.message ?: "تعذّر إضافة الموقع")
            }
        }
    }

    fun toggleSourceEnabled(sourceId: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleSourceEnabled(sourceId, enabled)
            _showRestartBanner.value = true
        }
    }

    fun deleteSource(sourceId: String) {
        viewModelScope.launch {
            repository.removeSource(sourceId)
            showToast("تم حذف المصدر")
        }
    }

    fun clearAllAddedSources() {
        viewModelScope.launch {
            repository.clearAllSources()
            showToast("تم حذف جميع المواقع المضافة")
        }
    }

    fun quickAddDemoSourcesIfEmpty() {
        openAddSiteDialog("")
    }

    fun runDiagnosticsForSource(sourceId: String) {
        viewModelScope.launch {
            _isRunningDiagnostics.value = true
            repository.runSourceDiagnostics(sourceId)?.let { report ->
                _diagnosticReports.value = _diagnosticReports.value + (sourceId to report)
            }
            _isRunningDiagnostics.value = false
        }
    }

    fun runAllSourcesDiagnostics() {
        viewModelScope.launch {
            _isRunningDiagnostics.value = true
            val currentSources = addedSources.value
            val updated = _diagnosticReports.value.toMutableMap()
            for (src in currentSources) {
                val report = repository.runSourceDiagnostics(src.id) ?: continue
                updated[src.id] = report
                _diagnosticReports.value = updated.toMap()
            }
            _isRunningDiagnostics.value = false
            showToast("اكتمل فحص المصادر")
        }
    }

    // ─── SOURCE CATALOG & FILTERING ───
    fun loadSourceCatalog(sourceId: String, page: Int = 1) {
        PipelineLogger.log(
            stage = PipelineStage.SOURCE_SELECTED,
            sourceId = sourceId,
            sourceName = sourceId,
            status = "SELECTED",
            details = "page=$page"
        )

        // Maintain a single stable collector for the active sourceId in Room
        catalogObserveJob?.cancel()
        catalogObserveJob = viewModelScope.launch {
            repository.observeMangaBySource(sourceId).collect { dbItems ->
                if (_catalogState.value.sourceId == sourceId && dbItems.isNotEmpty() && !_catalogState.value.requiresCloudflareGate) {
                    _catalogState.value = _catalogState.value.copy(items = dbItems)
                }
            }
        }

        catalogLoadJob?.cancel()
        catalogLoadJob = viewModelScope.launch {
            val initialVerificationState = repository.getSourceVerificationState(sourceId)
            val initialCached = if (initialVerificationState in listOf(
                    VerificationState.REQUIRED,
                    VerificationState.EXPIRED,
                    VerificationState.FAILED,
                    VerificationState.CANCELLED
                )
            ) {
                emptyList()
            } else {
                repository.getMangaBySourceOnce(sourceId)
            }

            _catalogState.value = _catalogState.value.copy(
                sourceId = sourceId,
                items = if (_catalogState.value.sourceId == sourceId && _catalogState.value.items.isNotEmpty() && initialCached.isNotEmpty()) {
                    _catalogState.value.items
                } else {
                    initialCached
                },
                isLoading = true,
                isLoadingNextPage = false,
                canLoadMore = true,
                currentPage = page,
                errorMessageAr = null,
                requiresCloudflareGate = false,
                showEmbeddedWebViewFallback = addedSources.value.any {
                    it.id == sourceId && it.status == SourceStatus.WEBVIEW_ONLY
                },
                verificationState = initialVerificationState
            )

            val res = withTimeoutOrNull(22_000L) {
                repository.refreshSourceCatalog(sourceId, page)
            }
            val latestVerState = repository.getSourceVerificationState(sourceId)
            val latestSession = registry.findById(sourceId)?.domain?.let { verificationManager.getSession(it) }

            when {
                res == null -> {
                    val fallback = repository.getMangaBySourceOnce(sourceId)
                    _catalogState.value = _catalogState.value.copy(
                        sourceId = sourceId,
                        items = if (fallback.isNotEmpty()) fallback else _catalogState.value.items,
                        isLoading = false,
                        verificationState = latestVerState,
                        errorMessageAr = if (fallback.isEmpty()) {
                            "استغرق الاتصال وقتاً طويلاً. يرجى التحقق من اتصال الإنترنت والمحاولة مجدداً."
                        } else null
                    )
                    PipelineLogger.log(
                        stage = PipelineStage.VIEWMODEL_STATE_UPDATED,
                        sourceId = sourceId,
                        resultCount = _catalogState.value.items.size,
                        status = if (_catalogState.value.items.isNotEmpty()) "CACHED_AFTER_TIMEOUT" else "TIMEOUT",
                        failureReason = "Catalog refresh exceeded 22s timeout"
                    )
                }
                res.isFailure -> {
                    val ex = res.exceptionOrNull()
                    val msg = ex?.message.orEmpty()
                    val fallback = repository.getMangaBySourceOnce(sourceId)
                    if (ex is VerificationRequiredException || msg == "SECURITY_VERIFICATION_REQUIRED") {
                        _catalogState.value = _catalogState.value.copy(
                            sourceId = sourceId,
                            items = emptyList(),
                            isLoading = false,
                            requiresCloudflareGate = true,
                            verificationState = if (latestVerState == VerificationState.NOT_REQUIRED) {
                                VerificationState.REQUIRED
                            } else {
                                latestVerState
                            },
                            verificationReasonAr = latestSession?.reasonAr ?: (ex as? VerificationRequiredException)?.detection?.reasonAr
                        )
                        PipelineLogger.log(
                            stage = PipelineStage.VIEWMODEL_STATE_UPDATED,
                            sourceId = sourceId,
                            resultCount = 0,
                            status = "REQUIRES_CLOUDFLARE",
                            failureReason = msg
                        )
                    } else if (msg == "NO_CONTENT_AFTER_VERIFICATION") {
                        // Verification succeeded (COMPLETED), but native extraction found 0 items -> show WebView fallback
                        _catalogState.value = _catalogState.value.copy(
                            sourceId = sourceId,
                            items = emptyList(),
                            isLoading = false,
                            requiresCloudflareGate = false,
                            showEmbeddedWebViewFallback = true,
                            verificationState = VerificationState.COMPLETED,
                            verificationReasonAr = "تم إكمال التحقق الأمني، ويتم عرض الموقع داخل WebView لأن الاستخراج المباشر مقيّد."
                        )
                    } else {
                        _catalogState.value = _catalogState.value.copy(
                            sourceId = sourceId,
                            items = if (fallback.isNotEmpty()) fallback else _catalogState.value.items,
                            isLoading = false,
                            verificationState = latestVerState,
                            showEmbeddedWebViewFallback = fallback.isEmpty() &&
                                registry.findById(sourceId) != null,
                            errorMessageAr = if (fallback.isEmpty()) {
                                msg.ifBlank { "تعذّر تحميل قائمة المانهوا" }
                            } else null
                        )
                        PipelineLogger.log(
                            stage = PipelineStage.VIEWMODEL_STATE_UPDATED,
                            sourceId = sourceId,
                            resultCount = _catalogState.value.items.size,
                            status = if (_catalogState.value.items.isNotEmpty()) "CACHED_ON_ERROR" else "FAILED",
                            failureReason = msg
                        )
                    }
                }
                else -> {
                    val fetched = res.getOrNull().orEmpty()
                    val allStored = repository.getMangaBySourceOnce(sourceId)
                    val finalItems = (fetched + allStored).distinctBy { it.id }
                    _catalogState.value = _catalogState.value.copy(
                        sourceId = sourceId,
                        items = finalItems,
                        isLoading = false,
                        isLoadingNextPage = false,
                        canLoadMore = fetched.size >= 8,
                        requiresCloudflareGate = false,
                        showEmbeddedWebViewFallback = false,
                        verificationState = latestVerState,
                        verificationReasonAr = latestSession?.reasonAr,
                        errorMessageAr = null
                    )
                    PipelineLogger.log(
                        stage = PipelineStage.VIEWMODEL_STATE_UPDATED,
                        sourceId = sourceId,
                        resultCount = finalItems.size,
                        status = "SUCCESS",
                        details = "page=$page"
                    )
                }
            }
        }
    }

    fun loadNextCatalogPage() {
        val state = _catalogState.value
        val sourceId = state.sourceId
        if (sourceId.isBlank() || state.isLoading || state.isLoadingNextPage || !state.canLoadMore || state.requiresCloudflareGate) {
            return
        }
        val nextPage = state.currentPage + 1
        _catalogState.value = state.copy(isLoadingNextPage = true)
        viewModelScope.launch {
            val res = withTimeoutOrNull(18_000L) {
                repository.refreshSourceCatalog(sourceId, nextPage)
            }
            val current = _catalogState.value
            if (current.sourceId != sourceId) return@launch
            if (res != null && res.isSuccess) {
                val newPageItems = res.getOrNull().orEmpty()
                val merged = (current.items + newPageItems).distinctBy { it.id }
                val addedNew = merged.size > current.items.size
                _catalogState.value = current.copy(
                    items = merged,
                    currentPage = if (addedNew) nextPage else current.currentPage,
                    isLoadingNextPage = false,
                    canLoadMore = addedNew && newPageItems.size >= 6
                )
            } else {
                _catalogState.value = current.copy(
                    isLoadingNextPage = false,
                    canLoadMore = false
                )
            }
        }
    }

    fun setCatalogSearchQuery(query: String) {
        _catalogState.value = _catalogState.value.copy(searchQuery = query)
    }

    fun setCatalogSearchActive(active: Boolean) {
        _catalogState.value = _catalogState.value.copy(
            isSearchActive = active,
            searchQuery = if (!active) "" else _catalogState.value.searchQuery
        )
    }

    fun toggleCatalogGenre(genre: String) {
        val current = _catalogState.value.selectedGenres.toMutableSet()
        if (current.contains(genre)) {
            current.remove(genre)
        } else {
            current.add(genre)
        }
        _catalogState.value = _catalogState.value.copy(selectedGenres = current)
    }

    fun clearCatalogFilters() {
        _catalogState.value = _catalogState.value.copy(
            selectedGenres = emptySet(),
            selectedSort = SortOption.LATEST_ADDED,
            selectedStatus = StatusFilter.ALL
        )
    }

    fun setCatalogSort(sort: SortOption) {
        _catalogState.value = _catalogState.value.copy(selectedSort = sort)
    }

    fun setCatalogStatusFilter(status: StatusFilter) {
        _catalogState.value = _catalogState.value.copy(selectedStatus = status)
    }

    // ─── MANGA DETAILS & CHAPTERS ───
    fun loadMangaDetails(mangaId: String) {
        detailsLoadJob?.cancel()
        detailsLoadJob = viewModelScope.launch {
            _detailsState.value = _detailsState.value.copy(
                isLoading = true,
                errorMessageAr = null
            )
            val res = withTimeoutOrNull(18_000L) {
                repository.loadMangaDetailsAndSyncChapters(mangaId)
            }
            val sizeMb = repository.getMangaStorageSizeMb(mangaId)
            if (res != null && res.isSuccess) {
                _detailsState.value = _detailsState.value.copy(
                    isLoading = false,
                    details = res.getOrNull(),
                    storageSizeMb = sizeMb,
                    errorMessageAr = null
                )
            } else {
                _detailsState.value = _detailsState.value.copy(
                    isLoading = false,
                    errorMessageAr = res?.exceptionOrNull()?.message ?: "تعذّر تحميل تفاصيل المانهوا"
                )
            }
        }
    }

    fun toggleChapterSortOrder() {
        _detailsState.value = _detailsState.value.copy(
            sortDescending = !_detailsState.value.sortDescending
        )
    }

    fun toggleFavorite(mangaId: String, currentlyFav: Boolean, category: String = "الكل") {
        viewModelScope.launch {
            repository.toggleFavorite(mangaId, currentlyFav, category)
            showToast(if (currentlyFav) "تمت الإزالة من المفضلة" else "تمت الإضافة إلى المفضلة")
        }
    }

    fun downloadSingleChapter(mangaId: String, chapterId: String) {
        viewModelScope.launch {
            repository.downloadSingleChapter(mangaId, chapterId)
            showToast("بدأ التنزيل")
            delay(1500L)
            val sizeMb = repository.getMangaStorageSizeMb(mangaId)
            _detailsState.value = _detailsState.value.copy(storageSizeMb = sizeMb)
        }
    }

    fun requestBatchDownload(chapters: List<MangaChapter>) {
        _detailsState.value = _detailsState.value.copy(
            showBatchConfirmDialog = true,
            pendingBatchChapters = chapters
        )
    }

    fun dismissBatchConfirmDialog() {
        _detailsState.value = _detailsState.value.copy(
            showBatchConfirmDialog = false,
            pendingBatchChapters = emptyList()
        )
    }

    fun confirmBatchDownload(mangaId: String) {
        val chapters = _detailsState.value.pendingBatchChapters
        _detailsState.value = _detailsState.value.copy(
            showBatchConfirmDialog = false,
            pendingBatchChapters = emptyList()
        )
        viewModelScope.launch {
            repository.downloadChapterBatch(mangaId, chapters.take(5))
            showToast("بدأ التنزيل")
        }
    }

    fun removeMangaFromStorage(mangaId: String) {
        viewModelScope.launch {
            repository.removeMangaFromStorage(mangaId)
            _detailsState.value = _detailsState.value.copy(storageSizeMb = 0.0)
            showToast("تمت إزالة الملفات المحملة من التخزين")
        }
    }

    // ─── NATIVE READER (Section 11) ───
    fun loadReaderChapter(
        mangaId: String,
        chapterId: String,
        initialPage: Int = 0,
        initialScrollOffset: Int = 0
    ) {
        readerLoadJob?.cancel()
        readerLoadJob = viewModelScope.launch {
            val chapterEntity = db.mangaDao().getChapterById(chapterId)
            val connectorBaseUrl = chapterEntity?.sourceId?.let { repository.resolveConnector(it)?.baseUrl }
            val resolvedReferer = chapterEntity?.remoteId?.takeIf { it.startsWith("http") } ?: connectorBaseUrl

            _readerState.value = ReaderUiState(
                isLoading = true,
                loadingProgress = 25,
                currentPageIndex = initialPage,
                currentScrollOffset = initialScrollOffset,
                refererUrl = resolvedReferer
            )
            val res = withTimeoutOrNull(14_000L) {
                repository.getReaderPages(chapterId)
            }
            if (res != null && res.isSuccess) {
                val pages = res.getOrThrow()
                val safeInitial = initialPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                _readerState.value = ReaderUiState(
                    isLoading = false,
                    loadingProgress = 100,
                    pages = pages,
                    currentPageIndex = safeInitial,
                    currentScrollOffset = initialScrollOffset.coerceAtLeast(0),
                    isControlsVisible = false,
                    refererUrl = resolvedReferer
                )
                saveReaderProgress(mangaId, chapterId, safeInitial, initialScrollOffset.coerceAtLeast(0))
            } else {
                _readerState.value = ReaderUiState(
                    isLoading = false,
                    refererUrl = resolvedReferer,
                    errorMessageAr = "تعذّر تحميل صفحات الفصل من المصدر"
                )
            }
        }
    }

    fun onReaderPageChanged(mangaId: String, chapterId: String, pageIndex: Int, scrollOffset: Int = 0) {
        val current = _readerState.value
        val pageChanged = current.currentPageIndex != pageIndex
        val offsetChangedSignificantly = abs(current.currentScrollOffset - scrollOffset) > 120
        if (pageChanged || offsetChangedSignificantly) {
            _readerState.value = current.copy(
                currentPageIndex = pageIndex,
                currentScrollOffset = scrollOffset
            )
            saveReaderProgress(mangaId, chapterId, pageIndex, scrollOffset)
        }
    }

    private fun saveReaderProgress(mangaId: String, chapterId: String, pageIndex: Int, scrollOffset: Int) {
        viewModelScope.launch {
            val ch = db.mangaDao().getChapterById(chapterId)
            repository.saveReadingProgress(
                mangaId = mangaId,
                chapterId = chapterId,
                chapterTitle = ch?.title ?: "1",
                pageIndex = pageIndex,
                scrollOffset = scrollOffset,
                totalPages = _readerState.value.pages.size
            )
        }
    }

    fun toggleReaderControls() {
        _readerState.value = _readerState.value.copy(
            isControlsVisible = !_readerState.value.isControlsVisible
        )
    }

    fun toggleReaderBookmark() {
        val next = !_readerState.value.isBookmarked
        _readerState.value = _readerState.value.copy(isBookmarked = next)
        showToast(if (next) "تم حفظ العلامة المرجعية للصفحة" else "تمت إزالة العلامة المرجعية")
    }

    fun retryFailedReaderPage(pageIndex: Int) {
        val failed = _readerState.value.failedPageIndices.toMutableSet()
        failed.remove(pageIndex)
        _readerState.value = _readerState.value.copy(failedPageIndices = failed)
    }

    // ─── DOWNLOAD CONTROLS ───
    fun pauseDownload(taskId: String) {
        viewModelScope.launch {
            repository.pauseDownload(taskId)
            showToast("لقد تم إيقاف التنزيلات مؤقتاً")
        }
    }

    fun resumeDownload(taskId: String) {
        viewModelScope.launch {
            repository.resumeDownload(taskId)
            showToast("تم استئناف التنزيل")
        }
    }

    fun cancelDownload(taskId: String) {
        viewModelScope.launch {
            repository.cancelDownload(taskId)
        }
    }

    fun pauseAllDownloads() {
        viewModelScope.launch {
            repository.pauseAllDownloads()
            showToast("لقد تم إيقاف التنزيلات مؤقتاً")
        }
    }

    fun cancelAllDownloads() {
        viewModelScope.launch {
            repository.cancelAllDownloads()
            showToast("تم إلغاء جميع التنزيلات الجارية")
        }
    }

    fun clearDownloadHistory() {
        viewModelScope.launch {
            repository.clearDownloadHistory()
            showToast("تم حذف سجل التنزيلات")
        }
    }

    // ─── SETTINGS & STORAGE ───
    fun refreshStorageStats() {
        viewModelScope.launch {
            _storageStats.value = repository.getStorageStats()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            repository.clearImageCache()
            refreshStorageStats()
            showToast("تم مسح ذاكرة التخزين المؤقتة")
        }
    }

    fun clearReadingHistory() {
        viewModelScope.launch {
            repository.clearReadingHistory()
            showToast("تم مسح السجل")
        }
    }

    fun setThemeStyle(style: AppThemeStyle) {
        viewModelScope.launch { preferences.setThemeStyle(style) }
    }

    fun setPureDarkMode(enabled: Boolean) {
        viewModelScope.launch { preferences.setPureDarkMode(enabled) }
    }

    fun setReaderMode(mode: ReaderMode) {
        viewModelScope.launch { preferences.setReaderMode(mode) }
    }

    fun setGridDisplayMode(mode: GridDisplayMode) {
        viewModelScope.launch { preferences.setGridDisplayMode(mode) }
    }

    fun setBooleanSetting(key: String, value: Boolean) {
        viewModelScope.launch { preferences.updateBoolean(key, value) }
    }

    fun setBrightnessValue(value: Int) {
        viewModelScope.launch { preferences.setBrightnessValue(value) }
    }

    fun setMaxConcurrentDownloads(value: Int) {
        viewModelScope.launch { preferences.setMaxConcurrentDownloads(value) }
    }

    fun addFavoriteCategory(category: String) {
        viewModelScope.launch {
            preferences.addFavoriteCategory(category)
            showToast("تمت إضافة الفئة: $category")
        }
    }
}
