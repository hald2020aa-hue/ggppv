package com.example.features.sourcemanager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.domain.models.MangaSummary
import com.example.sourceapi.SourceConfiguration

/** Manual CSS editor with live, site-backed preview; never treats fixture data as real results. */
@Composable
fun UniversalSourceSettingsScreen(
    configuration: SourceConfiguration,
    preview: List<MangaSummary>,
    isWorking: Boolean,
    error: String?,
    onChange: (SourceConfiguration) -> Unit,
    onPreview: () -> Unit,
    onSaveAndTest: () -> Unit,
    onImportJson: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    var jsonText by remember(configuration.domain) { mutableStateOf("") }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Universal Manga Source Engine", style = MaterialTheme.typography.titleLarge)
            Text("الموقع: ${configuration.domain} — الإعدادات خاصة بهذا النطاق")
        }
        item { EditorField("اسم المصدر", configuration.name) { onChange(configuration.copy(name = it)) } }
        item { EditorField("رابط القائمة النسبي", configuration.catalogPath) { onChange(configuration.copy(catalogPath = it)) } }
        item { EditorField("رابط البحث {query}", configuration.searchPath) { onChange(configuration.copy(searchPath = it)) } }
        item { EditorField("CSS: حاوية العمل", configuration.itemSelector) { onChange(configuration.copy(itemSelector = it)) } }
        item { EditorField("CSS: العنوان", configuration.titleSelector) { onChange(configuration.copy(titleSelector = it)) } }
        item { EditorField("CSS: رابط التفاصيل", configuration.detailLinkSelector) { onChange(configuration.copy(detailLinkSelector = it)) } }
        item { EditorField("CSS: الغلاف", configuration.coverSelector) { onChange(configuration.copy(coverSelector = it)) } }
        item { EditorField("CSS: الوصف", configuration.descriptionSelector) { onChange(configuration.copy(descriptionSelector = it)) } }
        item { EditorField("CSS: التصنيفات", configuration.genreSelector) { onChange(configuration.copy(genreSelector = it)) } }
        item { EditorField("CSS: حالة العمل", configuration.statusSelector) { onChange(configuration.copy(statusSelector = it)) } }
        item { EditorField("CSS: عنصر الفصل", configuration.chapterSelector) { onChange(configuration.copy(chapterSelector = it)) } }
        item { EditorField("CSS: رابط الفصل", configuration.chapterLinkSelector) { onChange(configuration.copy(chapterLinkSelector = it)) } }
        item { EditorField("CSS: صور القارئ", configuration.readerImageSelector) { onChange(configuration.copy(readerImageSelector = it)) } }
        item { EditorField("CSS: زر الصفحة التالية", configuration.nextPageSelector) { onChange(configuration.copy(nextPageSelector = it)) } }
        item {
            Text("إعدادات الاستيراد / التصدير", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = jsonText, onValueChange = { jsonText = it },
                label = { Text("JSON source config") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onImportJson(jsonText) }) { Text("استيراد") }
                OutlinedButton(onClick = { jsonText = configuration.toJson() }) { Text("عرض JSON للتصدير") }
            }
        }
        item {
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPreview, enabled = !isWorking) { Text("معاينة من الموقع") }
                Button(onClick = onSaveAndTest, enabled = !isWorking) { Text("حفظ واختبار") }
                TextButton(onClick = onNavigateBack) { Text("رجوع") }
            }
            if (isWorking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("المعاينة: ${preview.size} أعمال مستخرجة، ولا تعني نجاح فصول القراءة.")
        }
        items(preview, key = { it.id }) { manga ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(model = manga.coverUrl, contentDescription = "غلاف ${manga.title}",
                    modifier = Modifier.size(62.dp, 86.dp))
                Column {
                    Text(manga.title, style = MaterialTheme.typography.bodyMedium)
                    Text(manga.mangaUrl, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun EditorField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
fun AdultContentControlsScreen(
    adultEnabled: Boolean,
    hideCovers: Boolean,
    onSetEnabled: (Boolean, Boolean) -> Unit,
    onSetHideCovers: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var confirmed by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("إعدادات تصنيفات المحتوى", style = MaterialTheme.typography.titleLarge)
        Text("GENERAL / TEEN / MATURE / ADULT 18+ — يتم تصنيف الأعمال منفصلة عن نطاق المصدر.")
        Text("قسم البالغين معطّل افتراضياً. لا تفعّله إذا كنت دون السن القانوني في بلدك.")
        Row {
            Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
            Text("أؤكد أنني بلغت السن القانوني، وأن عرض المحتوى مسموح في بلدي.")
        }
        Button(onClick = { onSetEnabled(!adultEnabled, confirmed) },
            enabled = adultEnabled || confirmed) {
            Text(if (adultEnabled) "تعطيل محتوى 18+" else "تفعيل قسم 18+ بعد الإقرار")
        }
        Row {
            Switch(checked = hideCovers, onCheckedChange = onSetHideCovers)
            Text("إخفاء الأغلفة الحساسة افتراضياً")
        }
        Text("الإقرار الذاتي ليس فحص هوية أو إثبات عمر قانونياً، ولا يكفي في الدول التي تتطلب تحققاً رسمياً.")
        TextButton(onClick = onBack) { Text("رجوع") }
    }
}
