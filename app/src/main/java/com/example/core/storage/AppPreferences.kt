package com.example.core.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.models.AppThemeStyle
import com.example.domain.models.GridDisplayMode
import com.example.domain.models.ReaderMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "manga_reader_settings")

data class UserSettings(
    val themeStyle: AppThemeStyle = AppThemeStyle.DYNAMIC,
    val pureDarkMode: Boolean = false,
    val rtlMenus: Boolean = false,
    val hideToolbarOnScroll: Boolean = true,
    val showContinueReadingButton: Boolean = true,
    val showSourceIcons: Boolean = true,
    val startDestination: String = "التخزين",
    val recommendationNotifications: Boolean = true,
    val readerMode: ReaderMode = ReaderMode.VERTICAL_WEBTOON,
    val scrollByVolumeKeys: Boolean = false,
    val autoScrollSpeed: Int = 0,
    val alwaysWebtoonMode: Boolean = false,
    val autoDetectWebtoon: Boolean = true,
    val enableZoom: Boolean = true,
    val tapToOpenMenu: Boolean = true,
    val showBatteryAndTime: Boolean = false,
    val showPageNumber: Boolean = true,
    val showCommentsAtEnd: Boolean = true,
    val loadPagesWifiOnly: Boolean = false,
    val hideMenuButton: Boolean = false,
    val customBrightnessEnabled: Boolean = false,
    val customBrightnessValue: Int = 20,
    val keepScreenOn: Boolean = true,
    val checkNewChapters: Boolean = true,
    val checkNewChaptersWifiOnly: Boolean = true,
    val maxConcurrentDownloads: Int = 4,
    val downloadWifiOnly: Boolean = false,
    val gridDisplayMode: GridDisplayMode = GridDisplayMode.MEDIUM,
    val favoriteCategoriesCsv: String = "الكل,قراءة الآن,قراءة لاحقاً",
    val adultContentEnabled: Boolean = false,
    val adultAgeAcknowledged: Boolean = false,
    val hideSensitiveCovers: Boolean = true
)

class AppPreferences(private val context: Context) {

    private object Keys {
        val THEME_STYLE = stringPreferencesKey("theme_style")
        val PURE_DARK_MODE = booleanPreferencesKey("pure_dark_mode")
        val RTL_MENUS = booleanPreferencesKey("rtl_menus")
        val HIDE_TOOLBAR_ON_SCROLL = booleanPreferencesKey("hide_toolbar_on_scroll")
        val SHOW_CONTINUE_BUTTON = booleanPreferencesKey("show_continue_button")
        val SHOW_SOURCE_ICONS = booleanPreferencesKey("show_source_icons")
        val RECOMMENDATION_NOTIFS = booleanPreferencesKey("recommendation_notifs")
        val READER_MODE = stringPreferencesKey("reader_mode")
        val VOLUME_KEYS_SCROLL = booleanPreferencesKey("volume_keys_scroll")
        val AUTO_SCROLL_SPEED = intPreferencesKey("auto_scroll_speed")
        val ALWAYS_WEBTOON = booleanPreferencesKey("always_webtoon")
        val AUTO_DETECT_WEBTOON = booleanPreferencesKey("auto_detect_webtoon")
        val ENABLE_ZOOM = booleanPreferencesKey("enable_zoom")
        val TAP_TO_OPEN_MENU = booleanPreferencesKey("tap_to_open_menu")
        val SHOW_BATTERY_TIME = booleanPreferencesKey("show_battery_time")
        val SHOW_PAGE_NUMBER = booleanPreferencesKey("show_page_number")
        val SHOW_COMMENTS_END = booleanPreferencesKey("show_comments_end")
        val LOAD_WIFI_ONLY = booleanPreferencesKey("load_wifi_only")
        val HIDE_MENU_BTN = booleanPreferencesKey("hide_menu_btn")
        val CUSTOM_BRIGHTNESS = booleanPreferencesKey("custom_brightness")
        val BRIGHTNESS_VALUE = intPreferencesKey("brightness_value")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val CHECK_NEW_CHAPTERS = booleanPreferencesKey("check_new_chapters")
        val CHECK_NEW_WIFI_ONLY = booleanPreferencesKey("check_new_wifi_only")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("download_wifi_only")
        val GRID_DISPLAY_MODE = stringPreferencesKey("grid_display_mode")
        val FAVORITE_CATEGORIES = stringPreferencesKey("favorite_categories")
        val VERIFICATION_SESSIONS_METADATA = stringPreferencesKey("verification_sessions_metadata")
        val INITIAL_SOURCES_CLEARED_V2 = booleanPreferencesKey("initial_sources_cleared_v2")
        val DEFAULT_SOURCES_SEEDED_V3 = booleanPreferencesKey("default_sources_seeded_v3")
        val DELETED_BUILTIN_IDS = stringPreferencesKey("deleted_builtin_ids")
        val ADULT_ENABLED = booleanPreferencesKey("adult_enabled")
        val ADULT_ACKNOWLEDGED = booleanPreferencesKey("adult_age_acknowledged")
        val HIDE_SENSITIVE_COVERS = booleanPreferencesKey("hide_sensitive_covers")
    }

    val initialSourcesClearedFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.INITIAL_SOURCES_CLEARED_V2] ?: false
    }

    suspend fun markInitialSourcesCleared() {
        context.dataStore.edit { prefs ->
            prefs[Keys.INITIAL_SOURCES_CLEARED_V2] = true
        }
    }

    val defaultSourcesSeededFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_SOURCES_SEEDED_V3] ?: false
    }

    suspend fun markDefaultSourcesSeeded() {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_SOURCES_SEEDED_V3] = true
        }
    }

    val deletedBuiltInIdsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.DELETED_BUILTIN_IDS].orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    suspend fun markBuiltInSourceDeleted(sourceId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.DELETED_BUILTIN_IDS].orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
            current.add(sourceId)
            prefs[Keys.DELETED_BUILTIN_IDS] = current.joinToString(",")
        }
    }

    suspend fun unmarkBuiltInSourceDeleted(sourceId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.DELETED_BUILTIN_IDS].orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
            current.remove(sourceId)
            prefs[Keys.DELETED_BUILTIN_IDS] = current.joinToString(",")
        }
    }

    val verificationSessionsMetadataFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.VERIFICATION_SESSIONS_METADATA].orEmpty()
    }

    suspend fun saveVerificationSessionsMetadata(serializedMetadata: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VERIFICATION_SESSIONS_METADATA] = serializedMetadata
        }
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            themeStyle = prefs[Keys.THEME_STYLE]?.let {
                runCatching { AppThemeStyle.valueOf(it) }.getOrNull()
            } ?: AppThemeStyle.DYNAMIC,
            pureDarkMode = prefs[Keys.PURE_DARK_MODE] ?: false,
            rtlMenus = prefs[Keys.RTL_MENUS] ?: false,
            hideToolbarOnScroll = prefs[Keys.HIDE_TOOLBAR_ON_SCROLL] ?: true,
            showContinueReadingButton = prefs[Keys.SHOW_CONTINUE_BUTTON] ?: true,
            showSourceIcons = prefs[Keys.SHOW_SOURCE_ICONS] ?: true,
            recommendationNotifications = prefs[Keys.RECOMMENDATION_NOTIFS] ?: true,
            readerMode = prefs[Keys.READER_MODE]?.let {
                runCatching { ReaderMode.valueOf(it) }.getOrNull()
            } ?: ReaderMode.VERTICAL_WEBTOON,
            scrollByVolumeKeys = prefs[Keys.VOLUME_KEYS_SCROLL] ?: false,
            autoScrollSpeed = prefs[Keys.AUTO_SCROLL_SPEED] ?: 0,
            alwaysWebtoonMode = prefs[Keys.ALWAYS_WEBTOON] ?: false,
            autoDetectWebtoon = prefs[Keys.AUTO_DETECT_WEBTOON] ?: true,
            enableZoom = prefs[Keys.ENABLE_ZOOM] ?: true,
            tapToOpenMenu = prefs[Keys.TAP_TO_OPEN_MENU] ?: true,
            showBatteryAndTime = prefs[Keys.SHOW_BATTERY_TIME] ?: false,
            showPageNumber = prefs[Keys.SHOW_PAGE_NUMBER] ?: true,
            showCommentsAtEnd = prefs[Keys.SHOW_COMMENTS_END] ?: true,
            loadPagesWifiOnly = prefs[Keys.LOAD_WIFI_ONLY] ?: false,
            hideMenuButton = prefs[Keys.HIDE_MENU_BTN] ?: false,
            customBrightnessEnabled = prefs[Keys.CUSTOM_BRIGHTNESS] ?: false,
            customBrightnessValue = prefs[Keys.BRIGHTNESS_VALUE] ?: 20,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            checkNewChapters = prefs[Keys.CHECK_NEW_CHAPTERS] ?: true,
            checkNewChaptersWifiOnly = prefs[Keys.CHECK_NEW_WIFI_ONLY] ?: true,
            maxConcurrentDownloads = prefs[Keys.MAX_CONCURRENT_DOWNLOADS] ?: 4,
            downloadWifiOnly = prefs[Keys.DOWNLOAD_WIFI_ONLY] ?: false,
            gridDisplayMode = prefs[Keys.GRID_DISPLAY_MODE]?.let {
                runCatching { GridDisplayMode.valueOf(it) }.getOrNull()
            } ?: GridDisplayMode.MEDIUM,
            favoriteCategoriesCsv = prefs[Keys.FAVORITE_CATEGORIES] ?: "الكل,قراءة الآن,قراءة لاحقاً",
            adultContentEnabled = (prefs[Keys.ADULT_ENABLED] ?: false) && (prefs[Keys.ADULT_ACKNOWLEDGED] ?: false),
            adultAgeAcknowledged = prefs[Keys.ADULT_ACKNOWLEDGED] ?: false,
            hideSensitiveCovers = prefs[Keys.HIDE_SENSITIVE_COVERS] ?: true
        )
    }

    suspend fun setAdultContentEnabled(enabled: Boolean, adultAcknowledged: Boolean) {
        context.dataStore.edit {
            it[Keys.ADULT_ENABLED] = enabled && adultAcknowledged
            it[Keys.ADULT_ACKNOWLEDGED] = adultAcknowledged
        }
    }

    suspend fun setHideSensitiveCovers(hide: Boolean) {
        context.dataStore.edit { it[Keys.HIDE_SENSITIVE_COVERS] = hide }
    }

    suspend fun setThemeStyle(style: AppThemeStyle) {
        context.dataStore.edit { it[Keys.THEME_STYLE] = style.name }
    }

    suspend fun setPureDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PURE_DARK_MODE] = enabled }
    }

    suspend fun setReaderMode(mode: ReaderMode) {
        context.dataStore.edit { it[Keys.READER_MODE] = mode.name }
    }

    suspend fun setGridDisplayMode(mode: GridDisplayMode) {
        context.dataStore.edit { it[Keys.GRID_DISPLAY_MODE] = mode.name }
    }

    suspend fun updateBoolean(keyName: String, value: Boolean) {
        val key: Preferences.Key<Boolean> = when (keyName) {
            "rtlMenus" -> Keys.RTL_MENUS
            "hideToolbarOnScroll" -> Keys.HIDE_TOOLBAR_ON_SCROLL
            "showContinueReadingButton" -> Keys.SHOW_CONTINUE_BUTTON
            "showSourceIcons" -> Keys.SHOW_SOURCE_ICONS
            "recommendationNotifications" -> Keys.RECOMMENDATION_NOTIFS
            "scrollByVolumeKeys" -> Keys.VOLUME_KEYS_SCROLL
            "alwaysWebtoonMode" -> Keys.ALWAYS_WEBTOON
            "autoDetectWebtoon" -> Keys.AUTO_DETECT_WEBTOON
            "enableZoom" -> Keys.ENABLE_ZOOM
            "tapToOpenMenu" -> Keys.TAP_TO_OPEN_MENU
            "showBatteryAndTime" -> Keys.SHOW_BATTERY_TIME
            "showPageNumber" -> Keys.SHOW_PAGE_NUMBER
            "showCommentsAtEnd" -> Keys.SHOW_COMMENTS_END
            "loadPagesWifiOnly" -> Keys.LOAD_WIFI_ONLY
            "hideMenuButton" -> Keys.HIDE_MENU_BTN
            "customBrightnessEnabled" -> Keys.CUSTOM_BRIGHTNESS
            "keepScreenOn" -> Keys.KEEP_SCREEN_ON
            "checkNewChapters" -> Keys.CHECK_NEW_CHAPTERS
            "checkNewChaptersWifiOnly" -> Keys.CHECK_NEW_WIFI_ONLY
            "downloadWifiOnly" -> Keys.DOWNLOAD_WIFI_ONLY
            else -> return
        }
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setBrightnessValue(value: Int) {
        context.dataStore.edit { it[Keys.BRIGHTNESS_VALUE] = value.coerceIn(1, 100) }
    }

    suspend fun setMaxConcurrentDownloads(value: Int) {
        context.dataStore.edit { it[Keys.MAX_CONCURRENT_DOWNLOADS] = value.coerceIn(1, 8) }
    }

    suspend fun addFavoriteCategory(category: String) {
        val clean = category.trim()
        if (clean.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.FAVORITE_CATEGORIES] ?: "الكل,قراءة الآن,قراءة لاحقاً")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
            current.add(clean)
            prefs[Keys.FAVORITE_CATEGORIES] = current.joinToString(",")
        }
    }
}
