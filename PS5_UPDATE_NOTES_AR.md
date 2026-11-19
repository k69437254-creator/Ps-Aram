# تحديث PS Link — إصلاح البناء وربط PS5

هذه حزمة تحديث تراكبية للمستودع الموجود `PS-Link`، وليست مشروعًا جديدًا كاملًا ولا APK جاهزًا للتثبيت.

## ما الذي تعدّله؟

1. تصحيح استيراد ML Kit `DownloadConditions` الذي تسبب في فشل البناء الرابع.
2. إضافة فحص ثابت لسلسلة Chiaki-NG: الاكتشاف، تسجيل PS5، حفظ مفاتيح التسجيل، إنشاء جلسة Remote Play، سطح الفيديو، وتمرير أزرار التحكم.
3. توسيع فحوص المصدر حتى لا يُعتبر وجود الواجهات وحده دليلًا على اكتمال مسار الاتصال.
4. ترجمة حقول شاشة تسجيل PS5 وإرشادات رمز الربط إلى العربية.
5. تعطيل Android Auto Backup على تطبيق الهاتف لمنع نسخ بيانات تسجيل Remote Play الحساسة احتياطيًا.
6. تحسين GitHub Actions ليبني APK الهاتف والتلفاز بخطوتين منفصلتين، ويتحقق من Package ID لكل APK ويرفع كل نتيجة ناجحة على حدة.
7. إضافة دليل عربي لربط PS5 وخطة اختبار قبول عملية.

## طريقة التطبيق من Termux

بعد فك ZIP هذا في مجلد التنزيلات، نفّذ (عدّل مسار ملف ZIP فقط إذا حفظته باسم أو مكان مختلف):

```bash
cd ~/storage/downloads/PS-Link || exit 1
mkdir -p .pslink-update
unzip -o ~/storage/downloads/PS-Link-PS5-Connection-Update.zip -d .pslink-update
bash .pslink-update/apply_update.sh "$PWD"
```

ثم جهّز المصدر وافحصه:

```bash
bash scripts/bootstrap.sh
bash scripts/check-source.sh
```

إذا نجحت الفحوص، راجع التغييرات قبل الالتزام بها:

```bash
git status --short
git diff -- mobile/src/main/java/com/pslink/mobile/TranslationActivity.kt mobile/src/main/AndroidManifest.xml mobile/src/main/res/values-ar/strings.xml scripts/validate_package.py scripts/check-source.sh .github/workflows/android.yml
```

بعدها ارفع التحديث إلى GitHub بالطريقة المعتادة لديك ليبدأ البناء. لا يحتوي ZIP على مفاتيح توقيع أو بيانات دخول PSN.

## نقطة مهمة حول الاقتران

المحرك المضمن هو Chiaki-NG ويحتوي مسار Android الأصلي للتسجيل والبث والتحكم. شاشة تسجيل Android الحالية تتطلب PSN Account-ID بصيغة Base64 (ثمانية بايت) ورمز Link Device من PS5 من 8 أرقام. لا تحتوي واجهة Android الحالية على تسجيل دخول PSN تلقائيًا أو Public Lookup؛ لا تضع كلمة مرور PSN في أي حقل.

## ما لا تثبته الحزمة

الحزمة لا تحتوي APK مبنيًا، ولا تثبت اتصالًا حيًا بجهاز PS5. فحوصها ثابتة. يلزم نجاح GitHub Actions ثم تثبيت APK على هاتفك وإتمام الاختبار الفعلي وفق `payload/docs/PS5_CONNECTION_TEST_PLAN_AR.md`.
