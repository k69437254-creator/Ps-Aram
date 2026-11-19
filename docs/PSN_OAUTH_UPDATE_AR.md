# تحديث PS Link — تسجيل الدخول إلى PSN واستخراج Account-ID

هذه حزمة تحديث تراكبية للمستودع الحالي `PS-Link`، وليست APK جاهزًا.

## الإضافات

- زر يفتح تسجيل الدخول إلى PlayStation Network في متصفح Android الخارجي.
- حقل للصق رابط التحويل الذي يعرضه موقع Sony بعد تسجيل الدخول.
- تبادل رمز التفويض مع نقطة OAuth المستخدمة في إصدار Chiaki-NG المثبّت، ثم قراءة `user_id` وتحويله إلى معرّف PSN الثنائي ذي 8 بايت بصيغة Base64.
- التحقق من أن رابط التحويل هو HTTPS ومن مضيف Sony ومسار Remote Play المتوقع، مع مهلات اتصال وحدّ أقصى لحجم الاستجابة.
- عدم تخزين كلمة المرور أو access token أو refresh token، وعدم كتابة هذه القيم في السجلات.
- فحوص مصدر إضافية، وتحديث دليل الربط وخطة اختبار حساب PSN الفارغ واللعبة المشتركة.

## طريقة التثبيت من Termux

بعد تنزيل الحزمة إلى مجلد التنزيلات:

```bash
cd ~/storage/downloads/PS-Link || exit 1
mkdir -p .pslink-psn-oauth-update
unzip -o ~/storage/downloads/PS-Link-PSN-OAuth-Update.zip -d .pslink-psn-oauth-update
bash .pslink-psn-oauth-update/apply_update.sh "$PWD"
```

ثم نفّذ:

```bash
bash scripts/bootstrap.sh
bash scripts/check-source.sh
```

يجب أن تنجح الفحوص قبل رفع التحديث إلى GitHub. راجع التغييرات:

```bash
git status --short
git diff --check
git diff -- scripts/prepare_upstream.py scripts/validate_package.py scripts/check-ps5-wiring.sh mobile/src/main/res/values-ar/strings.xml docs/PS5_SETUP_AR.md docs/PS5_CONNECTION_TEST_PLAN_AR.md
```

الحزمة لا تنفذ `git add` أو `commit` أو `push`، ولا تبني APK بنفسها. بعد مراجعة التغييرات ورفعها بنفسك، ينفذ GitHub Actions البناء باستخدام Android SDK/NDK السحابي.

## تدفق تسجيل الدخول

1. افتح شاشة تسجيل PS5 واختر حساب PSN الفارغ الذي تملكه.
2. اضغط تسجيل الدخول إلى PlayStation Network.
3. سجّل الدخول من موقع Sony في المتصفح وتأكد أن الحساب الظاهر هو حساب PSN الفارغ الذي تملكه، لا حسابًا آخر ما زال المتصفح مسجلًا به.
4. انسخ رابط صفحة التحويل `redirect` كاملًا والصقه في التطبيق. لا ترسل الرابط إلى أي شخص لأنه يحتوي رمز تفويض مؤقتًا.
5. اضغط استخراج معرّف الحساب.
6. أدخل رمز ربط PS5 ذي الثمانية أرقام وسجّل الجهاز.

لا تدخل كلمة مرور PSN في التطبيق. لا تستخدم رابطًا من حسابك القديم الذي فقدت الوصول إليه.

## حدود مهمة

- تدفق Sony المستخدم منقول من مصدر Chiaki-NG المثبّت، لكنه ليس OAuth API موثقًا رسميًا وقد يتغير دون إشعار.
- تسجيل Remote Play بالحساب الفارغ لا يستعيد الحساب القديم ولا يغيّر مالكية GTA 6.
- يجب اختبار اتصال Remote Play وتشغيل اللعبة من ملف المستخدم الفارغ كلٌّ على حدة. لا تغيّر Console Sharing أو التراخيص أو ملفات المستخدم بغرض الاختبار.
- لم يُجرَ اتصال فعلي بجهاز PS5 هنا، ولا يمكن اعتبار APK جاهزًا قبل نجاح GitHub Actions والاختبار على جهاز حقيقي.
