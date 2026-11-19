# Language model packs

No machine-translation model binaries are bundled in this source archive.

PS Link uses the on-device Google ML Kit Translation API. The app explicitly downloads a
source-language -> Arabic model pack over Wi-Fi when the user requests it. Once the model
has downloaded successfully, translation is performed on-device and the app can translate
without an internet connection. ML Kit models are approximately 30 MB per language pack;
model sizes and supported languages can change.

Current source-language choices in the UI: Japanese, English, Chinese and Korean.
Target language: Arabic.

OCR recognizers for Latin, Japanese, Chinese and Korean are bundled as Maven dependencies.
The bundled Text Recognition v2 API does not offer Arabic script recognition in the current
integration. OCR is presently exposed for images selected by the user, not live Chiaki frames.

Do not place third-party model binaries here without checking their commercial-use license,
redistribution conditions, model card, attribution and runtime requirements.
