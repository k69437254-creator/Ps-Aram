# حزمة رفع جاهزية PS Link — المرحلة التالية

## ما الذي تضيفه هذه الحزمة؟

1. بوابة تحقق للمستقبلات المسجلة، بما فيها Samsung Tizen وLG webOS.
2. فحص صياغة جميع ملفات JavaScript الخاصة بالمستقبلات.
3. تشغيل اختبارات البروتوكول والاقتران وإعادة الاتصال وعناوين WebSocket وHLS الموجودة في المستودع.
4. التحقق من ملفات JSON ووجود الأهداف العشرة في `platforms/targets.json` دون تكرار.
5. إعادة تشغيل مدقق بنية Android/XML الحالي.
6. تشغيل الفحوصات نفسها تلقائياً عبر GitHub Actions عند الدفع إلى فرع التطوير أو فتح Pull Request.

## الملفات التي ستضاف

- `scripts/check-platform-regressions.sh`
- `.github/workflows/platform-regression.yml`
- `platforms/80-readiness-plan-ar.md`
- `platforms/repair-pack-manifest.json`

تسجل `platforms/targets.json` عشرة أهداف: Android Mobile، Android TV، Google TV، Fire TV، Tizen، webOS، Roku، Web، Xbox Edge وPlayStation. اختلاف الحالة في manifest مقصود؛ وجود مصدر أو اجتياز فحص ثابت لا يثبت اكتمال المنصة.

## ما الذي لا تدّعي الحزمة إنجازه؟

هذه الحزمة لا تحوّل النماذج الأولية إلى تطبيقات مكتملة، ولا تصنع توقيعات Tizen/webOS/Roku، ولا تثبت عمل PS5 أو مزامنة الصوت والصورة على أجهزة حقيقية. كما لا يجوز اعتبار نجاح الاختبارات الثابتة دليلاً على نجاح التشغيل الفعلي.

## حالة الأدلة الحالية — commit `451feecc5162e8b2d7bbe66d6ae2c483464c5eef`

على هذا الـcommit نجحت تدفقات GitHub Actions الخاصة باختبارات المستقبلات، لكن تدفق Android فشل في `bootstrap` قبل Gradle، ولم ينتج APK. نجحت محليًا بوابة المستقبلات، و13 اختبار Python لعقود المصدر، وثلاث عينات OCR عربية صناعية باستخدام Tesseract 5.5.4. هذه النتائج لا تثبت تشغيلًا على الأجهزة.

تسجل `platforms/targets.json` مصفوفة قبول مفصلة لكل هدف. حاليًا **0 من 10 منصات مقبولة بالكامل (0%)** وفق نسبة قبول المنصات، حيث لا يُقبل الهدف دون اجتياز جميع الشروط المنطبقة، بما فيها التشغيل الفعلي ودليل الجهاز. هذه النسبة تقيس قبول المنصات بالأدلة، وليست قياسًا لكمية الشيفرة المكتوبة.

| المنصة | بناء/حزمة | اختبار محاكاة/مصدر | اقتران/وسائط فعلية | جهاز فعلي |
|---|---|---|---|---|
| Android Mobile | فشل قبل Gradle | لم تُشغّل اختبارات Android | غير مختبر | NOT TESTED |
| Android TV | لم يُبنَ | لم تُشغّل اختبارات Android | غير مختبر | NOT TESTED |
| Google TV | لم يُبنَ | لم تُشغّل اختبارات Android | غير مختبر | NOT TESTED |
| Fire TV | لم يُبنَ | لم تُشغّل اختبارات Android | غير مختبر | NOT TESTED |
| Samsung Tizen | WGT غير مبني/موقّع | اختبارات محاكاة ناجحة | غير مختبر | NOT TESTED |
| LG webOS | IPK غير مبني/موقّع | اختبارات محاكاة ناجحة | غير مختبر | NOT TESTED |
| Roku | حزمة غير مبنية | اختبار عقد مصدر فقط | غير مختبر | NOT TESTED |
| Web Browser | لا تنطبق حزمة أصلية | اختبارات بروتوكول/محول ناجحة | غير مختبر | NOT TESTED |
| Xbox Edge | مستقبل متصفح، لا حزمة أصلية | اختبارات محاكاة ناجحة | غير مختبر على Xbox | NOT TESTED |
| PlayStation عبر Chiaki-NG | مسار Android لم يُبنَ | لم تُشغّل اختبارات Android | PS4/PS5 غير مختبر | NOT TESTED |

أدلة التشغيل الحالية:
- Android: [45481552752 — فشل مرحلة bootstrap](https://github.com/k69437254-creator/Ps-Aram/actions/runs/45481552752).
- مستقبلات Tizen/webOS: [45481552755 — نجاح اختبارات آلية فقط](https://github.com/k69437254-creator/Ps-Aram/actions/runs/45481552755).
- Regression للمستقبلات: [45481552764](https://github.com/k69437254-creator/Ps-Aram/actions/runs/45481552764).
- بروتوكول Web: [45481552833](https://github.com/k69437254-creator/Ps-Aram/actions/runs/45481552833).

المتبقي للإصدار: إعادة CI بعد إصلاح bootstrap، بناء APKs الأربعة والتحقق من معرّفاتها، توقيع/تغليف المنصات بمفاتيح المورّد، أجهزة فعلية لكل منصة، جلسة PS5 طويلة، إثبات صوت اللعبة ومزامنة A/V، مراجعة اتصال آمن تتجاوز `ws://`، وتأكيد ترخيص/منشأ نموذج Tesseract العربي.

يتضمن تطبيق الهاتف ملف OCR العربي `mobile/src/main/assets/tessdata/ara.traineddata`.
يتحقق اختبار المصدر من وجوده فقط؛ لا يثبت دقة التعرف أو توافقه على جهاز حقيقي.

## تشغيل بوابة التحقق

من جذر المستودع:

```bash
bash scripts/check-platform-regressions.sh
git status --short
```
