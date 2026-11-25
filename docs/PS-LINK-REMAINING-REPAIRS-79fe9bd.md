# سجل إصلاحات PS Link — خط أساس 79fe9bd

## إصلاحات الشيفرة المدرجة في الحزمة

- نقل Chiaki decoded PCM إلى جسر HLS مستقل ثم AAC-LC MediaCodec.
- اختيار مصدر صوت حصري: Chiaki PCM أولًا، أو Android Playback Capture بديلًا عند غياب PCM/فشل encoder.
- إعادة ترتيب حزم H.264/AAC حسب PTS ضمن حد ونافذة معلومتين.
- تكوين ADTS بحسب معدل العينة والقنوات الفعليين.
- زيادة معدل OCR إلى 350ms ووضع الطابع الزمني بعد التقاط PixelCopy.
- اختبارات وحدة للطابور/ADTS واختبارات source contract في بوابة CI.

## نتائج التحقق عند إعداد الحزمة

هذه الحزمة أُنشئت من ملفات GitHub المقروءة على الفرع `feature/platform-completion-pack`، HEAD `79fe9bd3d22d4f1405e400def670f91cca5b7910`. أداة إعداد ZIP لا تشغل Android SDK أو جهاز Roku/TV، لذلك لم تُسجل نتائج Gradle/Android أو أجهزة فعلية. نتائج التحقق المتاحة لهذه الحزمة تقتصر على سلامة ZIP، صياغة Python/Bash، واختبارات عقد Python المحاكية للطرف المصدر. لا تُعتبر Kotlin/Android compile ناجحة حتى GitHub Actions.

## بنود إصدار مفتوحة

- TLS موثوق من طرف إلى طرف للهاتف والمستقبلات.
- إثبات ترخيص ومصدر `ara.traineddata`.
- موافقة/توثيق واجهات Sony OAuth ودراسة تحسين التدفق دون تجاوز غير رسمي.
- اختبارات PS5 والهاتف وAndroid TV وTizen وwebOS وRoku والمتصفح وXbox Edge على أجهزة فعلية.
- قياس A/V drift طويل الأمد.
