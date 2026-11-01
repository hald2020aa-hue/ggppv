# تقرير التنفيذ الفني — Universal Manga Source Engine

**التاريخ:** 2026-11-01  
**المدخل:** نسخة المستخدم `manga-reader.zip`  
**الحالة:** **نسخة مصدرية قيد التحقق، وليست إصداراً مستقراً أو APK معتمداً.**

## أولاً: فحص الأصل

- `features/MainViewModel.kt` كان يحتوي `SourceRegistry(emptyList())`: الإصلاحات الأربعة المذكورة في الطلب **لم تكن مثبتة كلها** في الملف المرفوع.
- كان هناك مسار بدء تشغيل لحذف المصادر المحفوظة؛ أُلغي الحذف التلقائي.
- `ChapterDownloadWorker` كان ينشئ سجل موصلات مستقلاً لا يتضمن كل الموصلات الستة ولا الموصلات الديناميكية المحفوظة.
- كانت قاعدة Room تستخدم `fallbackToDestructiveMigration()`؛ أزيلت واستبدلت بمسار ترحيل معلن 2→3.
- كان بعض كود استخراج المصادر يخلط نتائج مصدر ببيانات من MangaDex ويستنتج فصولاً أو تواريخ غير مستخرجة مباشرة؛ أزيل هذا السلوك.

## ثانياً: التنفيذ المقدم

### المصادر والمحرك
- تسجيل الموصلات الستة الأصلية في `BuiltInSourceCatalog.createAll()`: MangaLek، MangaStarz، Team-X، Mangatek، Azora Manga، MangaDex. **التسجيل البرمجي لا يثبت نجاح اتصال هذه المواقع**.
- ربط الموصل المتخصص بمصادر المستخدم مع المحافظة على المعرف المخزن عبر `AliasedMangaSource`. لا يحل الموصل الديناميكي محل المتخصص تلقائياً.
- إضافة `SourceConfiguration` (إعدادات CSS ومسارات الكتالوج والبحث بصيغة JSON)، و`DynamicSourceAdapter` و`SourceAdapterFactory` و`SourceAdapterManager` و`UniversalSourceEngine` و`SourceDiscoveryEngine` و`SourceTestRunner`.
- تحليل HTML باستعمال Jsoup مع CSS مختلف لكل نطاق؛ استخراج قائمة الأعمال والتفاصيل والفصول وصفحات القراءة؛ اتصال HTTPS مضبوط على النطاق، ومن دون تجاوز CAPTCHA. فحص مبدئي لنوع HTML/JSON وRSS والـ JavaScript والتحقق ووجود روابط وصفحات متعددة.
- شاشة تحرير CSS مع استيراد/تصدير JSON ومعاينة من الموقع، إضافة إلى شاشة التشخيص القائمة. المعاينة لا تستبدل الموصل النشط.
- `SourceHealthChecker` يرفض إعلان SUPPORTED ما لم يجد بيانات وأغلفة وتفاصيل وفصولاً وصفحات وصوراً وبحثاً عملياً. PARTIALLY_SUPPORTED وNEEDS_CONFIGURATION وغيرها لا تعني نجاح المسار الكامل.

### التخزين والتصنيفات والقراءة
- تحديث Room من الإصدار 2 إلى 3: جدول `source_configs` وإضافة `manga.contentRating` بالقيمة الافتراضية GENERAL؛ منع الترحيل التدميري.
- تصنيف الأعمال بناءً على وسوم صريحة إلى GENERAL / TEEN / MATURE / ADULT_18_PLUS، وإخفاء 18+ افتراضياً من المكتبة والمفضلة والتاريخ وقوائم المصدر؛ ومنع التنزيل المباشر عندما يكون معطلاً.
- إخفاء الأغلفة المصنفة حساسة افتراضياً في مكوّن الأغلفة المستخدم في الشاشات القائمة.
- تعويض أعطال الصور ببيان فشل واضح، والحفاظ على رابط الصورة الأصلي عند إعادة المحاولة (لا تعديل روابط موقّعة).
- ربط تنزيلات الخلفية بسجل الموصلات المدمجة وبإعدادات المصادر المحفوظة في Room.
- الحفاظ على بنية القارئ والتنزيلات والـ WebView الموجودة، دون تغيير تصميمها الأساسي.

## ثالثاً: الملفات الجديدة

- `app/src/main/java/com/example/sourceapi/UniversalMangaSourceEngine.kt`
- `app/src/main/java/com/example/features/sourcemanager/UniversalSourceSettingsScreen.kt`
- `app/src/main/java/com/example/core/common/ContentPolicy.kt`
- `app/src/test/java/com/example/UniversalSourceEngineTest.kt`
- `IMPLEMENTATION_REPORT_AR.md` (هذا التقرير)

## رابعاً: الملفات المعدلة

- `.github/workflows/android-build.yml`
- `app/src/main/java/com/example/MainActivity.kt`
- `app/src/main/java/com/example/core/database/Entities.kt`
- `app/src/main/java/com/example/core/database/MangaDao.kt`
- `app/src/main/java/com/example/core/database/MangaDatabase.kt`
- `app/src/main/java/com/example/core/downloads/DownloadEngine.kt`
- `app/src/main/java/com/example/core/images/MangaImageLoader.kt`
- `app/src/main/java/com/example/core/storage/AppPreferences.kt`
- `app/src/main/java/com/example/data/repositories/MangaRepository.kt`
- `app/src/main/java/com/example/domain/models/MangaModels.kt`
- `app/src/main/java/com/example/features/MainViewModel.kt`
- `app/src/main/java/com/example/features/details/MangaDetailsScreen.kt`
- `app/src/main/java/com/example/features/home/StorageAndCatalogScreens.kt`
- `app/src/main/java/com/example/features/sourcemanager/SourceManagerAndBrowserScreen.kt`
- `app/src/main/java/com/example/sourceapi/MangaSource.kt`
- `app/src/main/java/com/example/sources/BuiltInSources.kt`
- `app/src/main/java/com/example/sources/MangaDexApiHelper.kt`
- `app/src/test/java/com/example/ExampleRobolectricTest.kt`
- `app/src/test/java/com/example/ExampleUnitTest.kt`

**ملفات النسخة الأصلية المحذوفة:** لا يوجد. **تنبيه حزم:** خط Cairo الموجود في أرشيفك الأصلي **مستثنى من الأرشيف المُسلَّم لأسباب إعادة توزيع الملفات**. احتفظ بالملف الأصلي `app/src/main/res/font/cairo.ttf` عند البناء المحلي، أو استعمل مسار تنزيل الخط في GitHub Actions الموجود بالمشروع.

## خامساً: تقرير الاختبارات والبناء (لا تدّعِ نجاح غير متحقق)

| بند | النتيجة | الدليل أو السبب |
|---|---|---|
| فحص الملفات الأصلية ومقارنتها | منجز | مقارنة المحتويات والأسماء بين ZIP والنسخة المعدلة |
| SQL الترحيل المجرد | نجح اختبار SQLite تجريبي محدود | أضيف عمود contentRating وجـدول source_configs مع بقاء صف اختباري قديم؛ **ليس** اختبار Room فعلياً |
| اختبار Parser ببيانات HTML تجريبية | الاختبارات **مكتوبة ولم تُشغّل** | `UniversalSourceEngineTest.kt` |
| اختبار Room على Android/Robolectric | **لم يُشغّل** | تعذر تحميل Gradle |
| اتصال حي بالمصادر الستة | **لم يُجرَ** | بيئة البناء/الاتصال لا تدعم تشغيل التطبيق واختبار HTTP الحي |
| فحص موقع جديد حي | **لم يُجرَ** | لا يوجد اختبار تطبيق أو اتصال حي ناجح |
| اختبار الواجهة والقارئ والتنزيل بلا إنترنت | **لم يُجرَ** | لم يتم تشغيل التطبيق على محاكي/هاتف |
| `:app:testDebugUnitTest` | **لم يبدأ** | Gradle Wrapper فشل عند تنزيل `gradle-9.3.1-bin.zip`: `UnknownHostException: services.gradle.org` |
| `:app:assembleDebug` | **لم يبدأ** | نفس عائق Gradle؛ **لا APK** |
| GitHub Commit | **غير متوفر** | الأرشيف لا يتضمن Remote وGitHub المتصل لا يحدد مستودع Manga Reader؛ لم تتم أي عملية رفع |

**المصادر العاملة بالكامل (مؤكدة):** لا يمكن اعتماد أي مصدر دون تشغيل اختبار حي كامل.  
**المصادر العاملة جزئياً (مؤكدة):** غير محددة؛ لم تجرِ اختبارات حية.  
**المصادر WebView-only (مؤكدة):** غير محددة؛ لم تجرِ اختبارات حية.  
**مواقع جديدة مجربة:** صفر.  
**ملفات APK مُسلَّمة:** صفر.

## سادساً: النواقص التي لا يجوز إخفاؤها

1. دعم JSON وREST API وRSS **لأي موقع** غير منفّذ آلياً؛ الفحص يكتشفها مبدئياً، ويستلزم موصلات مخصصة أو موثقة. MangaDex يستعمل موصله المتخصص الموجود.
2. المحتوى المرئي بالكامل عبر JavaScript لا يتحول تلقائياً إلى موصل أصلي. يبقى WebView المسار البديل، ما لم توجد واجهة رسمية تسمح بالاستخراج.
3. لا يوجد تحقق موثق قانوني من العمر: الموجود حالياً **إقرار ذاتي فقط**. تصنيف المحتوى يعتمد على الوسوم الصريحة ولا يضمن كشف جميع الأعمال الحساسة، ولا يوجد نظام مستقل للتحقق من حالات استغلال القاصرين.
4. شروط الاستخدام و`robots.txt` وحقوق التنزيل ليست مثبتة/مدققة لكل موقع؛ `robotsDisallowsRoot = null` تعني **غير معروف** وليست تصريحاً بالاستخراج.
5. لا يوجد إثبات أن كافة عناصر الصور والبحث والتصفية والفصول تعمل على مواقع الإنتاج، ولا اختبار فعلي للـ WebView/cookies/انتهاء التحقق على الأجهزة.
6. توجد اختبارات قديمة تعتمد على الشبكة الحية وتوقع نتائج مواقع متغيرة؛ عُلمت بأنها Integration Tests تحتاج شبكة، واستُبعدت من التشغيل كوحدات حتمية، ولا تعني أنها نجحت.
7. لم يجرِ اختبار قاعدة Room من نسخة حقيقية v2 تحتوي بيانات قارئ وتنزيلات؛ يجب استخدام اختبار MigrationHelper ونسخة احتياطية قبل توزيع تحديث.

## سابعاً: خطوات الاعتماد المطلوبة

1. بناء المشروع في بيئة تملك Android SDK / JDK 21 / Gradle 9.3.1 وشبكة تسمح بتنزيل التبعيات.  
   `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --stacktrace`
2. إصلاح أي أخطاء تجميع/اختبار تظهر ثم تشغيل APK على محاكي وجهاز حقيقي.
3. اختبار ترحيل قاعدة v2 بها مصادر/مفضلة/تقدم قراءة/تنزيلات إلى v3 والتحقق من بقاء البيانات.
4. اختبار جميع المصادر الستة كلٌ على حدة: الكتالوج، تفاصيل العمل، الفصول، الصور، البحث، WebView عند اللزوم، التنزيل، القراءة بلا إنترنت، وسياسات الموقع.
5. إضافة موقع HTML جديد دون كتابة كود، وتخصيص المحددات، وإثبات مساره الكامل مع سجل لعدد النتائج الحقيقي.
6. تنفيذ مزيد من ضوابط تصنيف البالغين والتحقق من العمر والقوانين قبل نشر التطبيق على متجر.
7. ربط المشروع بمستودع GitHub المقصود ورفع الملفات وتسجيل Commit بعد نجاح الاختبارات.

## ثامناً: أمر مهم

**لا تصنّف هذا التسليم «تطبيقاً مكتملاً ومستقراً» حتى تُنفَّذ خطوات الاعتماد أعلاه بالفعل.**
