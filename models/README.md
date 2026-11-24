# OCR and translation model data

Google ML Kit translation model binaries are not bundled. The phone downloads a
source-language-to-Arabic model when the user enables translation; after a successful
download, translation runs on-device. Model sizes and supported languages can change.

The Arabic OCR model is bundled separately at
`mobile/src/main/assets/tessdata/ara.traineddata` (about 2.5 MB). `LiveGameTranslator`
copies it into the app cache and initializes Tesseract with the `ara` language. Arabic
OCR output is displayed as-is; it does not require an Arabic-to-Arabic translation model.
Latin, Japanese, Chinese and Korean OCR recognizers are supplied by ML Kit dependencies.

The bundled model's precise source provenance and redistribution terms must be
confirmed and included in release notices before distribution. A local Tesseract
5.5.4 smoke test now exercises three generated Arabic samples with different size,
weight, and contrast; this is a narrow synthetic accuracy check, not an assessment
of game screenshots or Android-device performance. The Android OCR path uses
sparse-text page segmentation to better handle dialogue embedded in a full game
frame.
