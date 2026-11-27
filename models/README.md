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

## Arabic OCR model provenance and license

The bundled `ara.traineddata` file is from
`tesseract-ocr/tessdata`, commit
`cdd8a9ec438fc0b9f21635466196fe1c05efca16`.

The upstream tessdata README identifies Apache-2.0 as the license for
its traineddata files. The corresponding license text is included at
`LICENSES/Apache-2.0.txt`.

Verified bundled model:
- Size: 2,494,806 bytes
- SHA-256: `2005976778bbc14fc56a4ea8d43c6080847aeee72fcc2201488f240daca15c5b`

The Android wrapper dependency `com.rmtheis:tess-two:9.1.0` is a
separate component and is also declared Apache-2.0 in its published
Maven Central metadata.
