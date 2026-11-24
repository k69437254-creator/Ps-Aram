#!/usr/bin/env python3
"""Exercise the bundled Arabic Tesseract model on rendered text samples."""

import shutil
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TESSDATA = ROOT / "mobile/src/main/assets/tessdata"
FIXTURES = ROOT / "mobile/src/test/resources/fixtures/arabic-ocr"
SAMPLES = {
    "clear-large.png": "تم الاتصال بجهاز بلايستيشن",
    "bold-dark-background.png": "الرجاء إعادة المحاولة لاحقاً",
    "small-low-contrast.png": "حصل اللاعب الآن",
}


class ArabicOcrSamples(unittest.TestCase):
    def test_bundled_model_recognizes_arabic_across_rendering_variations(self):
        executable = shutil.which("tesseract")
        self.assertIsNotNone(executable, "Install Tesseract to run the Arabic OCR test")
        for filename, expected in SAMPLES.items():
            with self.subTest(sample=filename):
                image = FIXTURES / filename
                self.assertTrue(image.is_file(), f"Missing OCR sample: {image}")
                result = subprocess.run(
                    [
                        executable,
                        str(image),
                        "stdout",
                        "--tessdata-dir",
                        str(TESSDATA),
                        "-l",
                        "ara",
                        "--psm",
                        "11",
                    ],
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=30,
                )
                recognized = result.stdout.strip()
                for token in expected.split():
                    self.assertIn(token, recognized)


if __name__ == "__main__":
    unittest.main(verbosity=2)
