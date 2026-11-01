# Manga Reader — تطبيق قراءة المانهوا متعدد المصادر (Android)

تطبيق Android أصلي مبني بلغة **Kotlin** وواجهة **Jetpack Compose (Material 3)** لقراءة المانهوا والمانجا باللغة العربية مع دعم كامل لاتجاه **RTL**، إدارة المواقع والمصادر، المتصفح الداخلي، قاعدة بيانات **Room**، التنزيل الخلفي عبر **WorkManager**، والقراءة دون اتصال بالإنترنت.

---

## 1. المتطلبات التقنية وبنية المشروع

- **اللغة:** Kotlin `2.2.10`
- **واجهة المستخدم:** Jetpack Compose + Material 3 + خط Cairo المحلي
- **المعمارية:** Clean Architecture + MVVM (`StateFlow` + `ViewModel` + `Repository`)
- **قاعدة البيانات المحلية:** Room `2.7.0` (مع دعم `KSP` و`Migrations`)
- **الإعدادات:** Jetpack DataStore Preferences
- **الشبكة وتحليل الصفحات:** OkHttp + Retrofit + Jsoup `1.18.3` + Coil `2.7.0`
- **التنزيلات الخلفية:** AndroidX WorkManager `2.10.0`
- **أدوات البناء:** Android Gradle Plugin `9.1.1` + Gradle Wrapper `9.3.1` + `compileSdk 36` / `minSdk 24`

---

## 2. طريقة رفع المشروع إلى GitHub

يمكنك رفع المشروع إلى حسابك على GitHub بإحدى الطريقتين التاليتين:

### الطريقة الأولى: عبر Git من سطر الأوامر (Terminal)

1. أنشئ مستودعًا (Repository) جديدًا فارغًا على GitHub (مثلًا باسم `manga-reader-android`).
2. داخل مجلد المشروع، نفّذ الأوامر التالية بالترتيب:

```bash
# 1. تهيئة مستودع Git محلي وتحديد الفرع الرئيسي main
git init -b main

# 2. التأكد من صلاحية تنفيذ ملف gradlew قبل الرفع
chmod +x gradlew

# 3. إضافة جميع ملفات المشروع (سيقوم .gitignore باستثناء أي ملفات حساسة أو مؤقتة)
git add .

# 4. إنشاء أول Commit
git commit -m "Initial commit: Manga Reader Android app with GitHub Actions CI"

# 5. ربط المستودع المحلي بمستودعك على GitHub (استبدل الرابط برابط مستودعك)
git remote add origin https://github.com/<USERNAME>/<REPO_NAME>.git

# 6. رفع الكود إلى الفرع الرئيسي main
git push -u origin main
```

### الطريقة الثانية: التصدير المباشر من Google AI Studio
- من قائمة الإعدادات في Google AI Studio، يمكنك اختيار **Push to GitHub** مباشرةً أو **Export as ZIP** ثم فك الضغط ورفع الملفات إلى مستودعك.

---

## 3. كيف يعمل البناء التلقائي في GitHub Actions؟

يحتوي المشروع على ملف سير عمل جاهز في المسار:
```text
.github/workflows/android-build.yml
```

### متى يعمل البناء؟
1. **تلقائيًا عند أي `push`** إلى الفرع الرئيسي `main`.
2. **تلقائيًا عند إنشاء أو تحديث `pull_request`** يستهدف الفرع `main`.
3. **يدويًا (`workflow_dispatch`)** في أي وقت من داخل تبويب **Actions** في GitHub بالضغط على:
   `Actions` ➔ `Android CI & Build Debug APK` ➔ `Run workflow`.

### ماذا يفعل سير العمل؟
1. يسحب الكود المصدري للمشروع (`actions/checkout@v4`).
2. يثبّت بيئة **JDK 21** (`temurin`) وحزمة **Android SDK (API 36)**.
3. يهيئ ملف `.env` من القالب `.env.example` وينشئ شهادة توقيع التطوير القياسية (`debug.keystore`) تلقائيًا داخل بيئة البناء دون الحاجة لحفظ أي مفاتيح سرية في المستودع.
4. يشغّل اختبارات الوحدة واختبارات **Robolectric / Room**:
   ```bash
   ./gradlew :app:testDebugUnitTest --no-daemon --stacktrace
   ```
5. يبني نسخة **Debug APK**:
   ```bash
   ./gradlew :app:assembleDebug --no-daemon --stacktrace
   ```
6. يرفع ملف الـ APK الناتج من المسار `app/build/outputs/apk/debug/app-debug.apk` كملف **Artifact** جاهز للتنزيل باسم `MangaReader-debug-apk`.

---

## 4. خطوات تنزيل ملف الـ APK من GitHub Actions

بعد اكتمال البناء بعلامة خضراء (✅):
1. افتح مستودعك على موقع **GitHub**.
2. انتقل إلى تبويب **Actions** في الأعلى.
3. اضغط على آخر عملية بناء ناجحة تحت اسم **Android CI & Build Debug APK**.
4. انزل إلى أسفل الصفحة حتى قسم **Artifacts**.
5. اضغط على **`MangaReader-debug-apk`** لتنزيل الملف المضغوط، ثم فك الضغط عنه للحصول على ملف **`app-debug.apk`** وتثبيته مباشرةً على هاتفك بنظام Android.

---

## 5. الأمان وحماية المفاتيح

- ملف `.gitignore` مضبوط لمنع رفع أي ملفات حساسة مثل `.env` أو `local.properties` أو أي ملفات توقيع خاصة (`*.jks` و`*.keystore`).
- لا يحتوي المستودع على أي كلمات مرور خاصة أو مفاتيح API سرية.
