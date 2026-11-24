# OCR and translation model data

Google ML Kit translation model binaries are not bundled. The phone downloads a
source-language-to-Arabic model when the user enables translation; after a successful
download, translation runs on-device. Model sizes and supported languages can change.

The Arabic OCR model is bundled separately at
`mobile/src/main/assets/tessdata/ara.traineddata` (about 2.5 MB). `LiveGameTranslator`
copies it into the app cache and initializes Tesseract with the `ara` language. Arabic
OCR output is displayed as-is; it does not require an Arabic-to-Arabic translation model.
Latin, Japanese, Chinese and Korean OCR recognizers are supplied by ML Kit dependencies.

The source provenance and redistribution terms for the bundled Tesseract model must be
confirmed and included in release notices before distribution. The repository test checks
that the asset is present and non-empty; it does not measure OCR accuracy or validate the
model on a physical device.
