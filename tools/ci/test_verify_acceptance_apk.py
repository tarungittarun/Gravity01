#!/usr/bin/env python3
"""Acceptance preflight must reject absent, different, or non-installable release bytes."""
from pathlib import Path
import tempfile
import unittest
import zipfile

import verify_acceptance_apk as verifier


class PreflightTest(unittest.TestCase):
    SHA = "a" * 40

    def test_real_v2_certificate_format_and_legacy_v1_format(self):
        fingerprint = "c278749bdf33487caae760d3545ef82c2ce837a5c46c8e82df08599c1140b8a3"
        raw = "Verifies\nVerified using v2 scheme: true\nNumber of signers: 1\n" + \
              f"V2 Signer: certificate SHA-256 digest: {fingerprint}\n"
        self.assertEqual(fingerprint, verifier.cert_sha256(raw))
        self.assertEqual(fingerprint, verifier.cert_sha256(
            f"Signer #1: certificate SHA-256 digest: {fingerprint}"))
        self.assertIsNone(verifier.cert_sha256("Verified using v2 scheme: true"))

    def test_missing_release_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "missing.apk"
            with self.assertRaisesRegex(AssertionError, "release and same-run"):
                verifier.verify(p, p, self.SHA)

    def test_independent_download_cannot_differ(self):
        with tempfile.TemporaryDirectory() as tmp:
            release, build = Path(tmp) / "release.apk", Path(tmp) / "build.apk"
            release.write_bytes(b"download")
            build.write_bytes(b"same-sha-but-different-apk")
            with self.assertRaisesRegex(AssertionError, "RELEASE BYTES DIFFER"):
                verifier.verify(release, build, self.SHA)

    def test_synthetic_zip_is_not_mistaken_for_an_installed_app(self):
        with tempfile.TemporaryDirectory() as tmp:
            release, build = Path(tmp) / "release.apk", Path(tmp) / "build.apk"
            with zipfile.ZipFile(release, "w") as z:
                z.writestr("README", b"no compiled APK here" * 70000)
            build.write_bytes(release.read_bytes())
            with self.assertRaisesRegex(AssertionError, "compiled code absent"):
                verifier.verify(release, build, self.SHA)

    def test_invalid_commit_identity_rejected_before_hashing(self):
        with self.assertRaisesRegex(AssertionError, "invalid source SHA"):
            verifier.verify(Path("bad"), Path("bad"), "not-a-sha")


if __name__ == "__main__":
    unittest.main()
