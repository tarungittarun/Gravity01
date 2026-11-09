#!/usr/bin/env python3
"""Fail closed on home-screen/missing device evidence; never infer pixels from a log."""
from pathlib import Path
import json
import tempfile
import sys
import xml.etree.ElementTree as ET

PNG = b"\x89PNG\r\n\x1a\n"
NAMES = ("wimt-before-selected-source", "wimt-after-selected-source")


def verify(folder: Path, require_http_403: bool = True) -> dict:
    screens = folder / "screens"
    details = {}
    for name in NAMES:
        png, xml = screens / (name + ".png"), screens / (name + ".xml")
        if not png.exists() or png.stat().st_size < 1000 or png.read_bytes()[:8] != PNG:
            raise ValueError(f"missing/invalid in-test PNG: {name}")
        if not xml.exists() or xml.stat().st_size < 100:
            raise ValueError(f"missing in-test hierarchy: {name}")
        root = ET.parse(xml).getroot()
        nodes = list(root.iter("node"))
        app_nodes = [n for n in nodes if n.get("package") == "in.opentrack.india"]
        if not app_nodes:
            raise ValueError(f"not the foreground TrackTrain app: {name}")
        if not any(n.get("resource-id", "").endswith(":id/trainNo") for n in app_nodes):
            raise ValueError(f"train track not visible in captured app: {name}")
        if name.startswith("wimt-after") and require_http_403 and not any(
                "HTTP 403" in (n.get("text", "") + n.get("content-desc", "")) for n in app_nodes):
            raise ValueError("after-source hierarchy did not display HTTP 403")
        details[name] = {"pngBytes": png.stat().st_size, "appNodes": len(app_nodes)}
    return {"verifiedAppScreens": details, "displayedRefusalRequired": require_http_403}


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        with tempfile.TemporaryDirectory() as tmp:
            screens = Path(tmp) / "screens"
            screens.mkdir()
            for name in NAMES:
                (screens / (name + ".png")).write_bytes(PNG + b"0" * 2048)
                extra = '<node package="in.opentrack.india" text="Website refused HTTP 403" />' if "after" in name else ""
                (screens / (name + ".xml")).write_text(
                    '<hierarchy><node package="in.opentrack.india" '
                    'resource-id="in.opentrack.india:id/trainNo" />' + extra + '</hierarchy>')
            assert len(verify(Path(tmp))["verifiedAppScreens"]) == 2
            assert len(verify(Path(tmp), require_http_403=False)["verifiedAppScreens"]) == 2
            (screens / (NAMES[1] + ".xml")).write_text(
                '<hierarchy><node package="com.google.android.apps.nexuslauncher" /></hierarchy>')
            try:
                verify(Path(tmp))
                raise AssertionError("launcher was accepted as in-app screenshot")
            except ValueError:
                pass
        print("in-app screenshot gate rejects launcher and accepts matching hierarchy")
    elif len(sys.argv) in (2, 3) and (len(sys.argv) == 2 or sys.argv[2] == "--accepted"):
        try:
            print(json.dumps(verify(Path(sys.argv[1]),
                                    require_http_403=len(sys.argv) == 2), sort_keys=True))
        except (ValueError, OSError, ET.ParseError) as exc:
            print(f"WIMT_APP_SCREENSHOT_NOT_VERIFIED: {exc}", file=sys.stderr)
            sys.exit(1)
    else:
        raise SystemExit("usage: verify_wimt_screens.py <evidence-directory> [--accepted] | --self-test")
