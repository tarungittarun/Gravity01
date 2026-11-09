#!/usr/bin/env python3
"""Fail closed on published-vs-built bytes, signing, permissions and install identity.

This is run BEFORE any end-user journey. An emulator green on a different APK is not evidence.
"""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile


def output(*args):
    proc = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if proc.returncode:
        raise ValueError(f"{' '.join(map(str, args))} exited {proc.returncode}:\n{proc.stdout[-2000:]}")
    return proc.stdout


def cert_sha256(raw):
    """Android build tools use `Signer #1` or `V2 Signer` depending on scheme/version."""
    match = re.search(
        r"(?im)^(?:V\d+(?:\.\d+)? Signer|Signer\s+#\d+)\s*:\s*certificate SHA-256 digest:\s*([a-f0-9:]+)",
        raw,
    )
    return match.group(1).replace(":", "").lower() if match else None


def verify(release, artifact, expected_sha):
    assert len(expected_sha) == 40 and re.fullmatch(r"[0-9a-f]{40}", expected_sha), "invalid source SHA"
    assert release.is_file() and artifact.is_file(), "release and same-run build APK both required"
    built, downloaded = artifact.read_bytes(), release.read_bytes()
    print(f"release size={len(downloaded)} sha256={hashlib.sha256(downloaded).hexdigest()}", flush=True)
    assert built == downloaded, "RELEASE BYTES DIFFER from the same-run APK artifact"
    assert 1_000_000 < len(downloaded) < 20_000_000, "APK outside size policy"
    with zipfile.ZipFile(release) as archive:
        assert archive.testzip() is None, "corrupt APK ZIP"
        assert "classes.dex" in archive.namelist(), "compiled code absent"
        assert "assets/ref/refdb.dbgz" in archive.namelist(), "offline timetable absent"
    sdk = Path(os.environ.get("ANDROID_HOME", "/usr/local/lib/android/sdk")) / "build-tools"
    toolsets = sorted(sdk.iterdir())
    assert toolsets, "Android build tools unavailable: signature/manifest unverified"
    signer = str(toolsets[-1] / "apksigner")
    aapt = str(toolsets[-1] / "aapt")
    # apksigner success verifies the cryptographic signature. The stable certificate comparison is
    # INDEPENDENT and mandatory; include the raw output if Android changes its display format.
    raw = output(signer, "verify", "--verbose", "--print-certs", str(release))
    print("apksigner output:\n" + raw, flush=True)
    cert = cert_sha256(raw)
    assert cert, "apksigner succeeded but certificate SHA-256 was not parsed; key continuity unverified"
    key = output("keytool", "-list", "-v", "-keystore", "tools/ci/test-only-debug.p12",
                 "-storetype", "PKCS12", "-storepass", "android")
    expected = re.search(r"SHA256:\s*([a-fA-F0-9:]+)", key)
    assert expected and cert == expected.group(1).replace(":", "").lower(), "update certificate differs from stable test key"
    info = output(aapt, "dump", "badging", str(release))
    pkg = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", info)
    assert pkg and pkg.group(1) == "in.opentrack.india", "APK package identity wrong"
    gradle = Path("app/build.gradle").read_text()
    code = re.search(r"versionCode\s+(\d+)", gradle)
    name = re.search(r'versionName\s+"([^"]+)"', gradle)
    assert code and name and pkg.groups()[1:] == (code.group(1), name.group(1)), "APK version differs from this SHA"
    perms = set(re.findall(r"uses-permission: name='([^']+)'", output(aapt, "dump", "permissions", str(release))))
    allowed = set(re.findall(r'<uses-permission android:name="([^"]+)"', Path("app/src/main/AndroidManifest.xml").read_text()))
    # AndroidX Core contributes its own package-scoped, signature-protected receiver
    # permission during manifest merger. It is NOT a new dangerous Android permission.
    generated = "in.opentrack.india.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    assert not (perms - allowed - {generated}) and not (allowed - perms), \
        f"unexpected/missing permissions: {perms - allowed - {generated}} / {allowed - perms}"
    print(f"PASS: release bytes == Android artifact; valid signature, stable key, version={pkg.groups()[1:]}, permissions={sorted(perms)}")
    return hashlib.sha256(downloaded).hexdigest()


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3])
    except (AssertionError, OSError, ValueError, IndexError, zipfile.BadZipFile) as error:
        print(f"FAIL: APK acceptance preflight: {error}", file=sys.stderr, flush=True)
        sys.exit(1)
