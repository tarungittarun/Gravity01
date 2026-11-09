#!/usr/bin/env python3
"""Require the actual Android test's in-app PNG/XML, not a launcher snapshot."""
from pathlib import Path
import json
import struct
import sys
import xml.etree.ElementTree as ET


def verify(folder: Path) -> dict:
    png = folder / "offline-track.png"
    xml = folder / "offline-track.xml"
    data = png.read_bytes()
    if len(data) < 10_000 or data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("in-test app screenshot missing or not PNG")
    width, height = struct.unpack(">II", data[16:24])
    if min(width, height) < 300 or max(width, height) > 6000:
        raise ValueError("in-test screenshot has implausible dimensions")
    nodes = list(ET.parse(xml).getroot().iter("node"))
    foreground = [node for node in nodes if node.get("package") == "in.opentrack.india"]
    if not foreground or not any(n.get("resource-id", "").endswith(":id/trainNo") and
                                 n.get("text") == "20651" for n in foreground):
        raise ValueError("screen hierarchy is not the 20651 TrackTrain screen")
    return {"simulation": "PASS", "installedApkVisible": True,
            "screen": "TrackTrain 20651", "imageBytes": len(data),
            "imagePixels": [width, height], "websiteAccessVerified": False,
            "note": "Test-owned answers/offline APK; separate online source gate remains required"}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify_wimt_simulation.py <evidence-directory>")
    try:
        print(json.dumps(verify(Path(sys.argv[1])), sort_keys=True))
    except (ValueError, OSError, ET.ParseError, struct.error) as error:
        print(f"WIMT_SIMULATION_NOT_VERIFIED: {error}", file=sys.stderr)
        sys.exit(1)
