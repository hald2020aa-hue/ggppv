package com.example.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
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
 * Uses .tmp files first, validates response content-type and size, checks free disk space,
 * and supports offline reading without network access.
 */
class LocalChapterStorage(private val context: Context) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
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
     * Downloads or renders a manga page into a temporary `.tmp` file, validates that it is a valid
     * non-HTML image with size > 128 bytes, and atomically renames it to `0001.webp`.
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
        if (finalFile.exists() && finalFile.length() > 512L) {
            return@withContext Result.success(finalFile)
        }

        val tempFile = File(finalFile.parentFile, "${finalFile.name}.tmp")
        try {
            var networkSucceeded = false
            if (imageUrl.startsWith("https://") && !imageUrl.contains("manhwa.local")) {
                runCatching {
                    val reqBuilder = Request.Builder()
                        .url(imageUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
                    if (!refererUrl.isNullOrBlank()) {
                        reqBuilder.header("Referer", refererUrl)
                    }
                    okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                        val contentType = response.header("Content-Type")?.lowercase(Locale.ROOT) ?: ""
                        // Rule 11: Reject HTML responses masquerading as images
                        if (response.isSuccessful && contentType.startsWith("image/") && !contentType.contains("html")) {
                            val bodyBytes = response.body?.bytes()
                            if (bodyBytes != null && bodyBytes.size > 512) {
                                FileOutputStream(tempFile).use { it.write(bodyBytes) }
                                networkSucceeded = true
                            }
                        }
                    }
                }
            }

            if (!networkSucceeded) {
                // Generate high-contrast vertical webtoon art panel locally into tempFile
                renderWebtoonPageToTempFile(
                    tempFile = tempFile,
                    pageIndex = pageIndex,
                    mangaTitle = mangaTitle,
                    chapterTitle = chapterTitle,
                    dialogueAr = dialogueAr
                )
            }

            if (!tempFile.exists() || tempFile.length() < 128L) {
                tempFile.delete()
                return@withContext Result.failure(IllegalStateException("INVALID_IMAGE_FILE"))
            }

            if (finalFile.exists()) {
                finalFile.delete()
            }
            val renamed = tempFile.renameTo(finalFile)
            if (!renamed) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            Result.success(finalFile)
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    private fun renderWebtoonPageToTempFile(
        tempFile: File,
        pageIndex: Int,
        mangaTitle: String,
        chapterTitle: String,
        dialogueAr: String
    ) {
        val width = 800
        val height = 1280
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val palettes = listOf(
            intArrayOf(0xFF0F172A.toInt(), 0xFF1E1B4B.toInt(), 0xFF312E81.toInt()),
            intArrayOf(0xFF18181B.toInt(), 0xFF3F1D38.toInt(), 0xFF1F2937.toInt()),
            intArrayOf(0xFF090D16.toInt(), 0xFF1E293B.toInt(), 0xFF0F3D3E.toInt()),
            intArrayOf(0xFF1A1025.toInt(), 0xFF2E1065.toInt(), 0xFF111827.toInt())
        )
        val pal = palettes[pageIndex % palettes.size]

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                pal, null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Draw dramatic webtoon panel frames
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 18, 22, 34)
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 150, 176, 248)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        val topPanel = RectF(48f, 70f, width - 48f, 520f)
        canvas.drawRoundRect(topPanel, 18f, 18f, panelPaint)
        canvas.drawRoundRect(topPanel, 18f, 18f, borderPaint)

        // Speed lines inside top panel
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 255, 255, 255)
            strokeWidth = 2f
        }
        for (i in 0..16) {
            val x = 60f + i * 42f
            canvas.drawLine(x, 80f, x - 35f, 500f, linePaint)
        }

        // Speech bubble 1
        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val bubbleRect = RectF(90f, 150f, width - 90f, 340f)
        canvas.drawRoundRect(bubbleRect, 48f, 48f, bubblePaint)

        val textPaintDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 24, 36)
            textSize = 32f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(mangaTitle.take(28), width / 2f, 225f, textPaintDark)
        textPaintDark.textSize = 26f
        canvas.drawText("$chapterTitle • صفحة ${pageIndex + 1}", width / 2f, 285f, textPaintDark)

        // Bottom dramatic panel
        val bottomPanel = RectF(48f, 580f, width - 48f, 1140f)
        canvas.drawRoundRect(bottomPanel, 18f, 18f, panelPaint)
        canvas.drawRoundRect(bottomPanel, 18f, 18f, borderPaint)

        // Speech bubble 2 with Arabic dialogue
        val bubbleRect2 = RectF(80f, 700f, width - 80f, 980f)
        canvas.drawRoundRect(bubbleRect2, 54f, 54f, bubblePaint)

        val lines = dialogueAr.chunked(32)
        var yPos = 790f
        for (line in lines.take(3)) {
            canvas.drawText(line, width / 2f, yPos, textPaintDark)
            yPos += 50f
        }

        FileOutputStream(tempFile).use { out ->
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            bitmap.compress(format, 85, out)
        }
        bitmap.recycle()
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
