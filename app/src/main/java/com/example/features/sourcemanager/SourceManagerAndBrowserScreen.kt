package com.example.features.sourcemanager

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.core.common.isInternalOrLocalAddress
import com.example.core.verification.VerificationWebView
import com.example.domain.models.SourceDiagnosticReport
import com.example.domain.models.SourceInfo
import com.example.domain.models.SourceStatus
import com.example.features.AddSiteDialogState
import com.example.features.navigation.SourceBadgeIcon
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SourceManagerScreen(
    sources: List<SourceInfo>,
    showRestartBanner: Boolean,
    onNavigateBack: () -> Unit,
    onOpenAddSiteDialog: (String) -> Unit,
    onOpenBrowser: (String) -> Unit,
    onToggleSource: (String, Boolean) -> Unit,
    onDeleteSource: (String) -> Unit,
    onOpenSourceCatalog: (String) -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onClearAllSources: () -> Unit = {},
    onConfigureSource: (String) -> Unit = {},
    onOpenAdultSettings: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar matching Video 1 (00:01 & 00:50): Title "إدارة المواقع", Back arrow, Diagnostics, Browser & Add icons
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
                        modifier = Modifier.testTag("source_manager_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "إدارة المواقع",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier.testTag("top_diagnostics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Analytics,
                            contentDescription = "تشخيص المصادر"
                        )
                    }
                    IconButton(onClick = onOpenAdultSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "تصنيفات المحتوى")
                    }
                    if (sources.isNotEmpty()) {
                        IconButton(
                            onClick = { onOpenAddSiteDialog("") },
                            modifier = Modifier.testTag("top_add_site_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = "إضافة موقع"
                            )
                        }
                        IconButton(
                            onClick = { onOpenBrowser("https://www.google.com") },
                            modifier = Modifier.testTag("top_browser_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = "المتصفح"
                            )
                        }
                        IconButton(
                            onClick = onClearAllSources,
                            modifier = Modifier.testTag("top_clear_all_sources_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "حذف جميع المواقع"
                            )
                        }
                    }
                }
            }

            if (sources.isEmpty()) {
                // Exact Empty State from Video 1 (00:01): Puzzle icon + "لم تضف أي مواقع بعد" + "إضافة موقع" + "المتصفح"
                // No hardcoded/preset websites — user adds their sites manually.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = Color(0xFF454B59),
                            modifier = Modifier.size(96.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.empty_sources_message),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(22.dp))
                        Button(
                            onClick = { onOpenAddSiteDialog("") },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .width(200.dp)
                                .height(48.dp)
                                .testTag("add_site_empty_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("إضافة موقع", style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { onOpenBrowser("https://www.google.com") },
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .width(200.dp)
                                .height(48.dp)
                                .testTag("open_browser_empty_button")
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("المتصفح", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("sources_list")
                ) {
                    items(sources, key = { it.id }) { src ->
                        var showMenu by remember { mutableStateOf(false) }
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenSourceCatalog(src.id) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "ترتيب",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Switch(
                                        checked = src.isEnabled,
                                        onCheckedChange = { onToggleSource(src.id, it) },
                                        modifier = Modifier.testTag("source_switch_${src.id}")
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    SourceBadgeIcon(
                                        text = src.iconText,
                                        colorHex = src.iconColorHex,
                                        sizeDp = 38
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = src.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = src.status.labelAr,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = src.baseUrl,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "خيارات المصدر")
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("فتح أعمال المصدر") },
                                            onClick = {
                                                showMenu = false
                                                onOpenSourceCatalog(src.id)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("تشخيص المصدر") },
                                            onClick = {
                                                showMenu = false
                                                onOpenDiagnostics()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("إعداد محددات CSS والاختبار") },
                                            onClick = {
                                                showMenu = false
                                                onConfigureSource(src.id)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("فتح في المتصفح الداخلي") },
                                            onClick = {
                                                showMenu = false
                                                onOpenBrowser(src.baseUrl)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("حذف الموقع") },
                                            onClick = {
                                                showMenu = false
                                                onDeleteSource(src.id)
                                            }
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        }
                    }
                }
            }
        }

        if (showRestartBanner) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF232733),
                tonalElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                Text(
                    text = "عليك إغلاق التطبيق واعادة تشغيله من اجل تطبيق التغييرات",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }
    }
}

/**
 * Problem 9: Dedicated Source Diagnostics Screen (`شاشة تشخيص المصادر`).
 * Displays for each source:
 * - اسم المصدر (Source Name)
 * - النطاق (Domain)
 * - حالة الاتصال (Connection Status)
 * - حالة التحقق (Verification Status)
 * - حالة الموصل (Adapter Status: SUPPORTED, NEEDS_VERIFICATION, NEEDS_ADAPTER, WEBVIEW_ONLY, NETWORK_ERROR, PARSING_ERROR, UNSUPPORTED)
 * - عدد الأعمال المستخرجة (Extracted Manga Count)
 * - حالة الصور (Images Status)
 * - حالة الفصول (Chapters Status)
 * - آخر خطأ (Last Error)
 * - آخر اختبار (Last Test Timestamp)
 */
@Composable
fun SourceDiagnosticsScreen(
    sources: List<SourceInfo>,
    reports: Map<String, SourceDiagnosticReport>,
    isRunningDiagnostics: Boolean,
    onNavigateBack: () -> Unit,
    onRunSingleDiagnostic: (String) -> Unit,
    onRunAllDiagnostics: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

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
                    modifier = Modifier.testTag("diagnostics_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "رجوع"
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "تشخيص المصادر",
                    style = MaterialTheme.typography.headlineMedium
                )
            }

            if (sources.isNotEmpty()) {
                Button(
                    onClick = onRunAllDiagnostics,
                    enabled = !isRunningDiagnostics,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.testTag("run_all_diagnostics_button")
                ) {
                    if (isRunningDiagnostics) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("فحص الكل")
                }
            }
        }

        if (sources.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "لا توجد مواقع مضافة حالياً. قم بإضافة موقع من «إدارة المواقع» أولاً ثم افحصه هنا.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("diagnostics_list")
            ) {
                items(sources, key = { it.id }) { src ->
                    val report = reports[src.id]
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    SourceBadgeIcon(text = src.iconText, colorHex = src.iconColorHex, sizeDp = 36)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "اسم المصدر: ${src.name}",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "النطاق: ${src.domain}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                OutlinedButton(
                                    onClick = { onRunSingleDiagnostic(src.id) },
                                    enabled = !isRunningDiagnostics,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.testTag("diagnose_source_${src.id}")
                                ) {
                                    Text("فحص المصدر")
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            Spacer(modifier = Modifier.height(10.dp))

                            val adapterStatus = report?.adapterStatus ?: src.status
                            DiagnosticRow("حالة الاتصال", report?.connectionStatusAr ?: "في انتظار الفحص")
                            DiagnosticRow("حالة التحقق", report?.verificationStatusAr ?: "غير مطلوب")
                            DiagnosticRow("حالة الموصل", "${adapterStatus.name} (${adapterStatus.labelAr})")
                            DiagnosticRow("نوع المحرك", report?.siteArchitecture ?: "غير معروف")
                            DiagnosticRow("عدد الأعمال المستخرجة", "${report?.extractedMangaCount ?: 0}")
                            DiagnosticRow("عدد الأغلفة المحملة فعلياً", "${report?.loadedCoverCount ?: 0}")
                            DiagnosticRow("اختبار تفاصيل العمل", if (report?.detailsStatusOk == true) "نجح" else "لم ينجح")
                            DiagnosticRow("اختبار البحث", if (report?.searchStatusOk == true) "نجح" else "لم ينجح")
                            DiagnosticRow("صفحات القراءة", "${report?.extractedPagesCount ?: 0}")
                            DiagnosticRow(
                                "حالة الصور",
                                if (report == null) "لم يُختبر بعد" else if (report.imagesStatusOk) "صالحة (${report.sampleCoverUrl?.take(36)}...)" else "مفقودة"
                            )
                            DiagnosticRow(
                                "حالة الفصول",
                                if (report == null) "لم يُختبر بعد" else if (report.chaptersStatusOk) "متاحة (${report.extractedChaptersCount} فصل)" else "غير متاحة"
                            )
                            DiagnosticRow(
                                "آخر خطأ",
                                report?.lastError ?: "لا يوجد"
                            )
                            DiagnosticRow(
                                "آخر اختبار",
                                dateFormat.format(Date(report?.lastTestedAt ?: src.lastCheckedAt))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Dialogs for Adding a Site, Checking Compatibility, Manual Site Name Confirmation,
 * and Cloudflare Human Verification.
 */
@Composable
fun AddSiteAndVerificationDialogs(
    state: AddSiteDialogState,
    onInputChanged: (String) -> Unit,
    onStartCheck: () -> Unit,
    onDismiss: () -> Unit,
    onManualNameChanged: (String) -> Unit,
    onConfirmManualAdd: () -> Unit,
    onCompleteCloudflareVerify: (String?, String) -> Unit,
    onFailCloudflareVerify: (String) -> Unit = {},
    onCancelCloudflareVerify: () -> Unit = onDismiss
) {
    val clipboard = LocalClipboardManager.current

    // 1. Primary "إضافة موقع" Dialog
    if (state.isVisible && !state.showManualConfirmDialog && !state.showCloudflareDialog) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        text = "إضافة موقع",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = {
                                val clipText = clipboard.getText()?.text.orEmpty()
                                if (clipText.isNotBlank()) {
                                    onInputChanged(clipText)
                                }
                            },
                            modifier = Modifier.testTag("paste_domain_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "لصق الرابط"
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedTextField(
                            value = state.inputUrl,
                            onValueChange = onInputChanged,
                            label = { Text("أدخل عنوان موقع") },
                            placeholder = { Text("example.com") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("domain_input_field")
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.domain_privacy_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (state.isChecking) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = state.stepMessageAr,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (!state.errorMessageAr.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = state.errorMessageAr,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFEF5350)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("إلغاء")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onStartCheck,
                            enabled = !state.isChecking,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.testTag("confirm_check_domain_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("إضافة")
                        }
                    }
                }
            }
        }
    }

    // 2. Manual Site Name Confirmation Dialog ("إضافة موقع يدوياً")
    if (state.showManualConfirmDialog) {
        val connector = state.pendingValidationResult?.matchedSource
        Dialog(onDismissRequest = onDismiss) {
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
                    Text(
                        text = "إضافة موقع يدوياً",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start
                    )
                    Spacer(modifier = Modifier.height(18.dp))

                    if (connector != null) {
                        SourceBadgeIcon(
                            text = connector.iconText,
                            colorHex = connector.iconColorHex,
                            sizeDp = 72
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(72.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    OutlinedTextField(
                        value = state.manualSiteName,
                        onValueChange = onManualNameChanged,
                        label = { Text("اسم الموقع") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_site_name_input")
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("إلغاء")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onConfirmManualAdd,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.testTag("confirm_manual_site_button")
                        ) {
                            Text("تأكيد")
                        }
                    }
                }
            }
        }
    }

    // 3. Universal Website Verification WebView Dialog
    if (state.showCloudflareDialog) {
        VerificationWebView(
            domain = state.cloudflareDomain,
            sourceName = state.pendingValidationResult?.suggestedName ?: state.cloudflareDomain,
            stepMessageAr = state.cloudflareStepAr,
            onVerificationCompleted = { verifiedHtml, pageTitle ->
                onCompleteCloudflareVerify(verifiedHtml, pageTitle)
            },
            onVerificationFailed = { reasonAr ->
                onFailCloudflareVerify(reasonAr)
            },
            onCancel = onCancelCloudflareVerify
        )
    }
}

/**
 * Secure Internal WebView Browser with Floating "إضافة موقع" Button.
 * Supports real navigation for any website or Google search result without hardcoded site intercepts.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InternalBrowserScreen(
    initialUrl: String,
    onCloseBrowser: () -> Unit,
    onAddCurrentDomain: (String) -> Unit
) {
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("المتصفح") }
    var searchInput by remember { mutableStateOf(initialUrl) }
    var isEditingUrl by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onCloseBrowser,
                            modifier = Modifier.testTag("browser_close_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "إغلاق المتصفح")
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { isEditingUrl = !isEditingUrl }
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = pageTitle,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = currentUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(onClick = { isEditingUrl = !isEditingUrl }) {
                            Icon(Icons.Default.Search, contentDescription = "بحث أو إدخال رابط")
                        }

                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "خيارات المتصفح")
                            }
                            DropdownMenu(
                                expanded = showOverflow,
                                onDismissRequest = { showOverflow = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("نسخ URL") },
                                    onClick = {
                                        showOverflow = false
                                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(currentUrl))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("إضافة هذا الموقع") },
                                    onClick = {
                                        showOverflow = false
                                        onAddCurrentDomain(currentUrl)
                                    }
                                )
                            }
                        }
                    }

                    if (isEditingUrl) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = searchInput,
                                onValueChange = { searchInput = it },
                                placeholder = { Text("ابحث عن موقع مانهوا أو أدخل الرابط...") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                val q = searchInput.trim()
                                currentUrl = when {
                                    q.startsWith("https://") -> q
                                    q.startsWith("http://") -> "https://" + q.removePrefix("http://")
                                    q.contains(".") && !q.contains(" ") -> "https://$q"
                                    else -> "https://www.google.com/search?q=$q"
                                }
                                isEditingUrl = false
                            }) {
                                Text("انتقال")
                            }
                        }
                    }
                }
            }

            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val reqUrl = request?.url ?: return true
                                val rawUrlStr = reqUrl.toString()

                                // Unwrap Google Search redirect links (/url?q=...) so single tap opens the target site immediately
                                if (reqUrl.host?.contains("google.") == true && reqUrl.path == "/url") {
                                    val targetQ = reqUrl.getQueryParameter("q") ?: reqUrl.getQueryParameter("url")
                                    if (!targetQ.isNullOrBlank() && targetQ.startsWith("http")) {
                                        val httpsTarget = if (targetQ.startsWith("http://")) {
                                            "https://" + targetQ.removePrefix("http://")
                                        } else {
                                            targetQ
                                        }
                                        currentUrl = httpsTarget
                                        searchInput = httpsTarget
                                        view?.loadUrl(httpsTarget)
                                        return true
                                    }
                                }

                                val scheme = reqUrl.scheme?.lowercase() ?: ""
                                val host = reqUrl.host?.lowercase() ?: ""
                                if (scheme == "http" && !isInternalOrLocalAddress(host)) {
                                    val upgraded = "https://" + rawUrlStr.removePrefix("http://")
                                    currentUrl = upgraded
                                    searchInput = upgraded
                                    view?.loadUrl(upgraded)
                                    return true
                                }
                                if (scheme != "https" || isInternalOrLocalAddress(host)) {
                                    return true
                                }
                                currentUrl = rawUrlStr
                                searchInput = rawUrlStr
                                return false
                            }

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?
                            ) {
                                handler?.cancel()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (!url.isNullOrBlank()) {
                                    currentUrl = url
                                    searchInput = url
                                }
                                if (!view?.title.isNullOrBlank()) pageTitle = view?.title.orEmpty()
                            }
                        }
                        loadUrl(currentUrl)
                    }
                },
                update = { webView ->
                    if (webView.url != currentUrl && !currentUrl.startsWith("javascript:")) {
                        webView.loadUrl(currentUrl)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }

        // Floating "+ إضافة موقع" Button in bottom corner
        Button(
            onClick = { onAddCurrentDomain(currentUrl) },
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF283552),
                contentColor = Color(0xFFD6E2FF)
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .height(48.dp)
                .testTag("browser_floating_add_site_button")
        ) {
            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("إضافة موقع", style = MaterialTheme.typography.titleSmall)
        }
    }
}
