#!/usr/bin/env python3
from pathlib import Path
import hashlib
import unittest

ROOT = Path(__file__).resolve().parents[1]

class ArabicOcrLicenseTests(unittest.TestCase):
    def test_apache_license_file(self):
        path = ROOT / "LICENSES/Apache-2.0.txt"
        self.assertTrue(path.is_file(), "Apache-2.0 license file is missing")
        text = path.read_text(encoding="utf-8")
        self.assertIn("Apache License", text)
        self.assertIn("Version 2.0", text)
        self.assertIn(
            "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION",
            text,
        )

    def test_model_size_and_sha256(self):
        path = ROOT / "mobile/src/main/assets/tessdata/ara.traineddata"
        self.assertTrue(path.is_file(), "Arabic OCR model is missing")
        data = path.read_bytes()
        self.assertEqual(len(data), 2494806)
        self.assertEqual(
            hashlib.sha256(data).hexdigest(),
            "2005976778bbc14fc56a4ea8d43c6080847aeee72fcc2201488f240daca15c5b",
        )

    def test_release_gate_records_provenance_and_license(self):
        text = (ROOT / "models/ARA-MODEL-RELEASE-GATE.md").read_text(encoding="utf-8")
        for expected in (
            "cdd8a9ec438fc0b9f21635466196fe1c05efca16",
            "5643c61751c6ee890338ba7b31b965f8ceadb8d3",
            "5c6e9b9647c44920f307574d454709ed85c79bed",
            "LICENSES/Apache-2.0.txt",
            "2005976778bbc14fc56a4ea8d43c6080847aeee72fcc2201488f240daca15c5b",
            "حدود الإثبات",
        ):
            self.assertIn(expected, text)

    def test_third_party_notice_mentions_model_and_wrapper(self):
        text = (ROOT / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
        self.assertIn("tesseract-ocr/tessdata", text)
        self.assertIn("ara.traineddata", text)
        self.assertIn("com.rmtheis:tess-two:9.1.0", text)
        self.assertIn("Apache-2.0.txt", text)

    def test_models_readme_mentions_provenance(self):
        text = (ROOT / "models/README.md").read_text(encoding="utf-8")
        self.assertIn("tesseract-ocr/tessdata", text)
        self.assertIn("ara.traineddata", text)
        self.assertIn("Apache-2.0.txt", text)

if __name__ == "__main__":
    unittest.main(verbosity=2)
