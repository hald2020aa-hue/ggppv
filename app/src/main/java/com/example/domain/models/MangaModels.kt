package com.example.domain.models

enum class SourceStatus(val labelAr: String) {
    SUPPORTED("مدعوم (SUPPORTED)"),
    PARTIALLY_SUPPORTED("مدعوم جزئياً (PARTIALLY_SUPPORTED)"),
    NEEDS_CONFIGURATION("يحتاج إعداداً (NEEDS_CONFIGURATION)"),
    NEEDS_VERIFICATION("يتطلب تحققاً أمنياً (NEEDS_VERIFICATION)"),
    NEEDS_ADAPTER("يحتاج إلى موصل (NEEDS_ADAPTER)"),
    WEBVIEW_ONLY("عرض عبر المتصفح فقط (WEBVIEW_ONLY)"),
    NETWORK_ERROR("خطأ في الشبكة (NETWORK_ERROR)"),
    PARSING_ERROR("خطأ في الاستخراج (PARSING_ERROR)"),
    UNSUPPORTED("غير مدعوم (UNSUPPORTED)"),
    NEEDS_UPDATE("يحتاج إلى تحديث"),
    REQUIRES_VERIFICATION("يتطلب تحققاً أمنياً"),
    DISABLED("معطل"),
    ERROR("خطأ في المصدر")
}

enum class SourceCapability(val labelAr: String) {
    POPULAR_MANGA("قائمة الأعمال الشائعة"),
    LATEST_UPDATES("آخر التحديثات"),
    SEARCH_MANGA("البحث في الأعمال"),
    MANGA_DETAILS("تفاصيل العمل"),
    MANGA_CHAPTERS("قائمة الفصول"),
    CHAPTER_PAGES("صفحات الفصل"),
    PAGINATION("تعدد الصفحات"),
    CATEGORIES("التصنيفات والفئات"),
    FILTERS("الفرز والتصفية")
}

enum class ContentRating { GENERAL, TEEN, MATURE, ADULT_18_PLUS }

data class SourceDiagnosticReport(
    val sourceId: String,
    val sourceName: String,
    val domain: String,
    val connectionStatusAr: String,
    val verificationStatusAr: String,
    val adapterStatus: SourceStatus,
    val siteArchitecture: String = "HTML",
    val extractedMangaCount: Int = 0,
    val sampleCoverUrl: String? = null,
    val imagesStatusOk: Boolean = false,
    val loadedCoverCount: Int = 0,
    val detailsStatusOk: Boolean = false,
    val searchStatusOk: Boolean = false,
    val chaptersStatusOk: Boolean = false,
    val extractedChaptersCount: Int = 0,
    val pagesStatusOk: Boolean = false,
    val extractedPagesCount: Int = 0,
    val capabilities: Set<SourceCapability> = emptySet(),
    val lastError: String? = null,
    val lastTestedAt: Long = System.currentTimeMillis()
)

enum class DownloadStatus(val labelAr: String) {
    WAITING("في الانتظار"),
    DOWNLOADING("جار التنزيل..."),
    PAUSED("موقوف مؤقتاً"),
    COMPLETED("اكتمل التنزيل"),
    FAILED("فشل التنزيل"),
    CANCELLED("ألغيت")
}

enum class ErrorType(val titleAr: String, val messageAr: String) {
    NETWORK_ERROR("خطأ في الشبكة", "تعذّر الاتصال بالإنترنت. يرجى التحقق من الشبكة والمحاولة مجدداً."),
    HTTP_ERROR("خطأ في استجابة الخادم", "أعاد الموقع رمز استجابة غير متوقع. حاول مرة أخرى لاحقاً."),
    SOURCE_UNSUPPORTED("الموقع غير مدعوم", "هذا النطاق لا يمتلك موصلاً متوافقاً مع التطبيق حالياً."),
    SOURCE_PARSE_ERROR("خطأ في تحليل الصفحة", "تغيرت بنية HTML الخاصة بالموقع أو تعذر استخراج البيانات."),
    SOURCE_UPDATE_REQUIRED("يتطلب تحديث الموصل", "بنية الموقع تغيرت ويحتاج موصل المصدر إلى تحديث."),
    SECURITY_VERIFICATION_REQUIRED("عليك فتح الصفحة وإكمال التحقق", "يطلب هذا الموقع إتمام التحقق البشري (Cloudflare) للمتابعة."),
    IMAGE_LOAD_ERROR("خطأ في التحميل", "تعذّر تحميل صورة الصفحة. حاول مجدداً!"),
    CHAPTER_EMPTY("لم يتم العثور على فصول", "لا توجد صفحات أو فصول متاحة لهذا العمل حالياً."),
    DOWNLOAD_FAILED("فشل التنزيل", "تعذّر إكمال تنزيل الفصل بسبب انقطاع الاتصال أو رفض الطلب."),
    STORAGE_FULL("مساحة التخزين ممتلئة", "لا توجد مساحة كافية على الجهاز لحفظ صفحات الفصل."),
    DATABASE_ERROR("خطأ في قاعدة البيانات", "حدث خطأ أثناء حفظ أو استرجاع البيانات المحلية.")
}

data class AppError(
    val type: ErrorType,
    val customMessage: String? = null,
    val sourceId: String? = null,
    val targetUrl: String? = null
)

data class SourceInfo(
    val id: String,
    val name: String,
    val domain: String,
    val baseUrl: String,
    val language: String = "ar",
    val iconText: String = "M",
    val iconColorHex: Long = 0xFF3F51B5,
    val isEnabled: Boolean = true,
    val status: SourceStatus = SourceStatus.SUPPORTED,
    val version: Int = 1,
    val lastCheckedAt: Long = System.currentTimeMillis()
)

data class MangaSummary(
    val id: String,
    val sourceId: String,
    val title: String,
    val coverUrl: String?,
    val mangaUrl: String,
    val latestChapter: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Float? = null,
    val status: String? = null,
    val contentRating: ContentRating = ContentRating.GENERAL
)

data class MangaChapter(
    val id: String,
    val mangaId: String,
    val sourceId: String,
    val remoteId: String,
    val title: String,
    val chapterNumber: Float?,
    val uploadDate: Long?,
    val dateLabel: String = "",
    val isRead: Boolean = false,
    val isDownloaded: Boolean = false,
    val lastReadPage: Int = 0
)

data class MangaDetails(
    val id: String,
    val sourceId: String,
    val title: String,
    val alternativeTitles: List<String>,
    val coverUrl: String?,
    val description: String,
    val author: String?,
    val artist: String?,
    val status: String?,
    val genres: List<String>,
    val rating: Float?,
    val chapters: List<MangaChapter>
)

data class MangaPage(
    val index: Int,
    val imageUrl: String,
    val localPath: String? = null,
    val chapterTitle: String = "",
    val dialoguePreviewAr: String = ""
)

data class ReadingProgress(
    val mangaId: String,
    val chapterId: String,
    val pageIndex: Int,
    val scrollOffset: Int,
    val updatedAt: Long
)

data class DownloadTask(
    val id: String,
    val chapterId: String,
    val mangaId: String,
    val mangaTitle: String,
    val chapterTitle: String,
    val coverUrl: String?,
    val totalPages: Int,
    val downloadedPages: Int,
    val failedPages: Int,
    val status: DownloadStatus,
    val createdAt: Long = System.currentTimeMillis()
) {
    val progress: Int
        get() = if (totalPages > 0) (downloadedPages * 100) / totalPages else 0

    val progressFloat: Float
        get() = if (totalPages > 0) downloadedPages.toFloat() / totalPages.toFloat() else 0f

    val remainingSecondsEstimate: Int
        get() = ((totalPages - downloadedPages).coerceAtLeast(0)) * 2
}

data class PageResult<T>(
    val items: List<T>,
    val page: Int,
    val hasNextPage: Boolean
)

enum class AppThemeStyle(
    val titleAr: String,
    val primaryPreviewColor: Long,
    val secondaryPreviewColor: Long,
    val bgPreviewColor: Long
) {
    DEFAULT("افتراضي", 0xFF96B0F8, 0xFFCE93D8, 0xFF14161F),
    DYNAMIC("ديناميك", 0xFFA5B4FC, 0xFF93C5FD, 0xFF14161F),
    GREEN("أخضر", 0xFF81C784, 0xFFA5D6A7, 0xFF121A14),
    PURPLE("أرجواني", 0xFFB39DDB, 0xFFCE93D8, 0xFF181322),
    MIDNIGHT("منتصف الليل", 0xFFCE93D8, 0xFFF48FB1, 0xFF140F1D),
    RED("أحمر", 0xFFE57373, 0xFFEF9A9A, 0xFF1F1212),
    TAKO("تاكو", 0xFFFFAB91, 0xFFFFCC80, 0xFF1C1514),
    TEAL("أزرق مخضر", 0xFF4DB6AC, 0xFF80CBC4, 0xFF101D1B),
    TIDAL_WAVE("موجة مد و جزر", 0xFF64B5F6, 0xFF4DD0E1, 0xFF0E1924),
    YIN_YANG("ين & يانغ", 0xFFE0E0E0, 0xFF9E9E9E, 0xFF121212),
    YOTSUBA("يوتسوبا", 0xFFFFB74D, 0xFFA1887F, 0xFF1E1610)
}

enum class ReaderMode(val labelAr: String) {
    VERTICAL_WEBTOON("تمرير رأسي (ويبتون)"),
    HORIZONTAL_RTL("أفقي (من اليمين إلى اليسار)"),
    HORIZONTAL_LTR("أفقي (من اليسار إلى اليمين)"),
    SINGLE_PAGE("صفحة واحدة")
}

enum class GridDisplayMode(val labelAr: String, val minCellWidthDp: Int) {
    LIST("القائمة", 320),
    SMALL("صغير", 105),
    MEDIUM("متوسط", 130),
    LARGE("كبير", 165)
}

enum class SortOption(val labelAr: String) {
    LATEST_ADDED("آخر الاضافات"),
    MOST_VIEWED("الاكثر مشاهدة"),
    NEWEST("جديد"),
    ALPHABETICAL("أبجدي"),
    RATING("التقييم"),
    POPULAR("الشائع")
}

enum class StatusFilter(val labelAr: String) {
    ALL("الكل"),
    COMPLETED("مكتمل"),
    ONGOING("مستمر"),
    CANCELLED("ملغي"),
    HIATUS("متوقف")
}
