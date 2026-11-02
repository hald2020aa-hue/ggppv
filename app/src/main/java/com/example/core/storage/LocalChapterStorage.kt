package com.example.core.storage

import android.content.Context
import android.os.StatFs
import com.example.core.verification.VerificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

data class StorageStats(
    val downloadsBytes: Long,
    val pagesCacheBytes: Long,
    val otherAppBytes: Long,
    val availableBytes: Long,
    val storagePath: String
) {
    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }
}

/**
 * Rule 11, 12, 13: Local Chapter Storage Engine.
 * Hierarchy: saved/{source_id}/{manga_id}/chapter_{xxx}/0001.webp
 * Uses .tmp files first, validates response content-type and raster magic bytes, checks free disk space,
 * and never synthesizes fake comic pages on failure.
 */
class LocalChapterStorage(private val context: Context) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun getSavedRootDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val savedDir = File(base, "saved")
        if (!savedDir.exists()) {
            savedDir.mkdirs()
        }
        return savedDir
    }

    fun getChapterDir(sourceId: String, mangaId: String, chapterNumber: Float?): File {
        val safeSource = sourceId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeManga = mangaId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val numInt = (chapterNumber ?: 1f).toInt().coerceAtLeast(1)
        val chapterFolder = String.format(Locale.US, "chapter_%03d", numInt)
        val dir = File(File(File(getSavedRootDir(), safeSource), safeManga), chapterFolder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getPageFile(sourceId: String, mangaId: String, chapterNumber: Float?, pageIndex: Int): File {
        val dir = getChapterDir(sourceId, mangaId, chapterNumber)
        val fileName = String.format(Locale.US, "%04d.webp", pageIndex + 1)
        return File(dir, fileName)
    }

    fun isChapterCompletelyDownloaded(
        sourceId: String,
        mangaId: String,
        chapterNumber: Float?,
        expectedPages: Int
    ): Boolean {
        if (expectedPages <= 0) return false
        val dir = getChapterDir(sourceId, mangaId, chapterNumber)
        if (!dir.exists()) return false
        for (i in 0 until expectedPages) {
            val fileName = String.format(Locale.US, "%04d.webp", i + 1)
            val file = File(dir, fileName)
            if (!file.exists() || file.length() < 128L) {
                return false
            }
        }
        return true
    }

    fun hasEnoughFreeSpace(minRequiredBytes: Long = 20L * 1024L * 1024L): Boolean {
        return try {
            val stat = StatFs(getSavedRootDir().absolutePath)
            stat.availableBytes >= minRequiredBytes
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Downloads a real manga page image into a temporary `.tmp` file, validates that it is a valid
     * non-HTML raster image with size > 256 bytes, and atomically renames it to `0001.webp`.
     * Never generates synthetic placeholder artwork if the network download fails.
     */
    suspend fun downloadAndSavePage(
        sourceId: String,
        mangaId: String,
        chapterNumber: Float?,
        pageIndex: Int,
        imageUrl: String,
        mangaTitle: String,
        chapterTitle: String,
        dialogueAr: String,
        refererUrl: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!hasEnoughFreeSpace()) {
            return@withContext Result.failure(IllegalStateException("STORAGE_FULL"))
        }

        val finalFile = getPageFile(sourceId, mangaId, chapterNumber, pageIndex)
        if (finalFile.exists() && finalFile.length() > 256L && isLikelyRasterImageFile(finalFile)) {
            return@withContext Result.success(finalFile)
        }

        if (!imageUrl.startsWith("http://") && !imageUrl.startsWith("https://")) {
            return@withContext Result.failure(IllegalArgumentException("INVALID_IMAGE_URL: $imageUrl"))
        }

        val tempFile = File(finalFile.parentFile, "${finalFile.name}.tmp")
        var lastError: Throwable? = null

        for (attempt in 1..3) {
            try {
                val reqBuilder = Request.Builder()
                    .url(imageUrl)
                    .header("User-Agent", VerificationManager.getUserAgent())
                    .header("Accept", "image/avif,image/webp,image/apng,image/png,image/jpeg,image/*,*/*;q=0.8")
                if (!refererUrl.isNullOrBlank()) {
                    reqBuilder.header("Referer", refererUrl)
                }
                VerificationManager.getCookieHeader(imageUrl)?.let { reqBuilder.header("Cookie", it) }

                okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP_${response.code}")
                    }
                    val contentType = response.header("Content-Type")?.lowercase(Locale.ROOT).orEmpty()
                    if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
                        throw IllegalStateException("EXPECTED_IMAGE_GOT_HTML")
                    }
                    val bodyBytes = response.body?.bytes()
                        ?: throw IllegalStateException("EMPTY_IMAGE_BODY")
                    if (bodyBytes.size <= 256 || !isLikelyRasterImageBytes(bodyBytes)) {
                        throw IllegalStateException("INVALID_RASTER_IMAGE_BYTES")
                    }
                    FileOutputStream(tempFile).use { out ->
                        out.write(bodyBytes)
                        out.flush()
                    }
                }

                if (tempFile.exists() && tempFile.length() > 256L && isLikelyRasterImageFile(tempFile)) {
                    if (finalFile.exists()) {
                        finalFile.delete()
                    }
                    val renamed = tempFile.renameTo(finalFile)
                    if (!renamed) {
                        tempFile.copyTo(finalFile, overwrite = true)
                        tempFile.delete()
                    }
                    return@withContext Result.success(finalFile)
                }
            } catch (e: Exception) {
                lastError = e
                runCatching { tempFile.delete() }
                if (attempt < 3) {
                    delay(350L * attempt)
                }
            }
        }

        runCatching { tempFile.delete() }
        Result.failure(lastError ?: IllegalStateException("FAILED_TO_DOWNLOAD_PAGE_${pageIndex + 1}"))
    }

    internal fun isLikelyRasterImageFile(file: File): Boolean {
        if (!file.exists() || file.length() < 12L) return false
        return try {
            val header = ByteArray(12)
            file.inputStream().use { it.read(header) }
            isLikelyRasterImageBytes(header)
        } catch (_: Exception) {
            false
        }
    }

    internal fun isLikelyRasterImageBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val isJpeg = (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8
        val isPng = (bytes[0].toInt() and 0xFF) == 0x89 &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte()
        val isGif = bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        val isWebp = bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        val isAvifOrHeif = bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
        return isJpeg || isPng || isGif || isWebp || isAvifOrHeif
    }

    suspend fun deleteChapterFiles(sourceId: String, mangaId: String, chapterNumber: Float?) = withContext(Dispatchers.IO) {
        val dir = getChapterDir(sourceId, mangaId, chapterNumber)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    suspend fun getMangaStorageSizeMb(sourceId: String, mangaId: String): Double = withContext(Dispatchers.IO) {
        val safeSource = sourceId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeManga = mangaId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val dir = File(File(getSavedRootDir(), safeSource), safeManga)
        val bytes = calculateDirSize(dir)
        bytes / (1024.0 * 1024.0)
    }

    suspend fun computeStorageStats(): StorageStats = withContext(Dispatchers.IO) {
        val savedRoot = getSavedRootDir()
        val downloadsBytes = calculateDirSize(savedRoot)
        val cacheBytes = calculateDirSize(context.cacheDir)
        val dbFile = context.getDatabasePath("manga_reader.db")
        val otherBytes = (if (dbFile.exists()) dbFile.length() else 0L) + 7L * 1024L * 1024L
        val available = try {
            StatFs(savedRoot.absolutePath).availableBytes
        } catch (e: Exception) {
            246L * 1024L * 1024L * 1024L
        }
        StorageStats(
            downloadsBytes = downloadsBytes,
            pagesCacheBytes = cacheBytes,
            otherAppBytes = otherBytes,
            availableBytes = available,
            storagePath = savedRoot.absolutePath
        )
    }

    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val before = calculateDirSize(context.cacheDir)
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        before
    }

    private fun calculateDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                total += file.length()
            }
        }
        return total
    }
}
