package com.example.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.images.MangaCoverImage
import com.example.domain.models.DownloadStatus
import com.example.domain.models.DownloadTask
import java.util.Locale

@Composable
fun DownloadsScreen(
    tasks: List<DownloadTask>,
    onNavigateBack: () -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onPauseAll: () -> Unit,
    onCancelAll: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenManga: (String) -> Unit
) {
    var showOverflow by remember { mutableStateOf(false) }

    val activeTasks = remember(tasks) {
        tasks.filter {
            it.status == DownloadStatus.DOWNLOADING ||
                it.status == DownloadStatus.WAITING ||
                it.status == DownloadStatus.PAUSED
        }
    }
    val finishedTasks = remember(tasks) {
        tasks.filter {
            it.status == DownloadStatus.COMPLETED ||
                it.status == DownloadStatus.CANCELLED ||
                it.status == DownloadStatus.FAILED
        }
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
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.testTag("downloads_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "التنزيلات",
                    style = MaterialTheme.typography.headlineMedium
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (activeTasks.isNotEmpty()) {
                    TextButton(
                        onClick = onPauseAll,
                        modifier = Modifier.testTag("downloads_pause_all_button")
                    ) {
                        Text(
                            text = "إيقاف ||",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                Box {
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "خيارات التنزيلات")
                    }
                    DropdownMenu(
                        expanded = showOverflow,
                        onDismissRequest = { showOverflow = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("إلغاء الكل") },
                            onClick = {
                                showOverflow = false
                                onCancelAll()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("حذف السجل") },
                            onClick = {
                                showOverflow = false
                                onClearHistory()
                            }
                        )
                    }
                }
            }
        }

        if (tasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "لا توجد تنزيلات حالية.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("downloads_list")
            ) {
                if (activeTasks.isNotEmpty()) {
                    item {
                        Text(
                            text = "قيد التنفيذ",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(activeTasks, key = { it.id }) { task ->
                        ActiveDownloadCard(
                            task = task,
                            onPause = { onPauseTask(task.id) },
                            onResume = { onResumeTask(task.id) },
                            onCancel = { onCancelTask(task.id) }
                        )
                    }
                }

                if (finishedTasks.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "اليوم",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(finishedTasks, key = { it.id }) { task ->
                        FinishedDownloadCard(
                            task = task,
                            onClick = { onOpenManga(task.mangaId) },
                            onRetry = { onResumeTask(task.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveDownloadCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val pctFormatted = String.format(Locale.US, "%.1f%%", (task.downloadedPages * 100f) / task.totalPages.coerceAtLeast(1))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MangaCoverImage(
                    imageUrl = task.coverUrl,
                    title = task.mangaTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(54.dp, 76.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.mangaTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = if (task.status == DownloadStatus.PAUSED) "موقوف مؤقتاً • ${task.chapterTitle}" else "جار التنزيل • ${task.chapterTitle}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (task.status == DownloadStatus.PAUSED) "عالقة (${task.downloadedPages}/${task.totalPages})" else "خلال ${task.remainingSecondsEstimate} ثانية (${task.downloadedPages}/${task.totalPages})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = pctFormatted,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { task.progressFloat },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (task.status == DownloadStatus.PAUSED) {
                    OutlinedButton(
                        onClick = onResume,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("استكمال")
                    }
                } else {
                    OutlinedButton(
                        onClick = onPause,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.testTag("pause_download_${task.id}")
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("إيقاف")
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.testTag("cancel_download_${task.id}")
                ) {
                    Text("إلغاء")
                }
            }
        }
    }
}

@Composable
private fun FinishedDownloadCard(
    task: DownloadTask,
    onClick: () -> Unit,
    onRetry: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                MangaCoverImage(
                    imageUrl = task.coverUrl,
                    title = task.mangaTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(50.dp, 70.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.mangaTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when (task.status) {
                            DownloadStatus.COMPLETED -> "اكتمل التنزيل • ${task.chapterTitle} (${task.totalPages} صفحة)"
                            DownloadStatus.CANCELLED -> "ألغيت • ${task.chapterTitle}"
                            else -> "فشل التنزيل • ${task.chapterTitle}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELLED) {
                TextButton(onClick = onRetry) {
                    Text("إعادة محاولة")
                }
            }
        }
    }
}
