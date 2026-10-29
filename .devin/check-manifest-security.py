#!/usr/bin/env python3
# check-manifest-security.py — SEC14 manifest security gate
#
# Lints every app/src/**/AndroidManifest.xml for:
#   1. Components with an intent-filter but no explicit
#      android:exported (ambiguous export — install-blocking on
#      Android 12+ and a stealth-export trap on older targets).
#   2. android:exported="true" components outside the reviewed
#      EXPORTED_ALLOWLIST (SEC09 established that only MainActivity
#      and DownloadCompleteReceiver may be reachable cross-process).
#   3. uses-permission / uses-permission-sdk-23 entries outside
#      PERMISSION_ALLOWLIST (permission-creep tripwire — adding a
#      permission requires a deliberate allowlist edit in review).
#   4. android:debuggable="true" outside debug-source manifests.
#
# Cheap enough to run standalone: ./.devin/check-manifest-security.py
# Exits non-zero on any finding, mirroring check-engine-audit.sh.

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"
COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")

# Gradle provides the namespace when the manifest omits package=.
DEFAULT_PACKAGE = "com.arktosbrowser.mobile"

# Reviewed exported surface (SEC09). MainActivity is the browser entry
# point; DownloadCompleteReceiver must receive the DownloadManager
# broadcast. Anything new must be argued in review, then allowlisted.
EXPORTED_ALLOWLIST = {
    "com.arktosbrowser.mobile.MainActivity",
    "com.arktosbrowser.mobile.download.DownloadCompleteReceiver",
}

PERMISSION_ALLOWLIST = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.RECORD_AUDIO",
    "android.permission.CAMERA",
    "android.permission.MODIFY_AUDIO_SETTINGS",
    "com.android.launcher.permission.INSTALL_SHORTCUT",
    # Needed so the Downloads screen can hand a finished APK to the
    # package installer (notification tap → install prompt).
    "android.permission.REQUEST_INSTALL_PACKAGES",
    # Sync's LAN leg runs mDNS discovery; Android filters inbound
    # multicast unless the app holds both this permission and a
    # WifiManager.MulticastLock (acquired in ensureTransport).
    "android.permission.CHANGE_WIFI_MULTICAST_STATE",
}


def resolve_name(raw, package):
    if not raw:
        return ""
    if raw.startswith("."):
        return package + raw
    if "." not in raw:
        return package + "." + raw
    return raw


def main():
    root = Path(__file__).resolve().parent.parent
    manifests = sorted(root.glob("app/src/*/AndroidManifest.xml"))
    if not manifests:
        print("manifest-audit: FAIL — no AndroidManifest.xml found under app/src", file=sys.stderr)
        return 1

    failures = []
    exported_seen = []
    perms_seen = set()

    for path in manifests:
        rel = path.relative_to(root)
        in_debug_sources = "debug" in path.parts
        try:
            tree = ET.parse(path)
        except ET.ParseError as e:
            failures.append(f"{rel}: unparsable manifest ({e})")
            continue

        manifest = tree.getroot()
        package = manifest.get("package") or DEFAULT_PACKAGE
        app = manifest.find("application")
        if app is None:
            continue

        if app.get(ANDROID + "debuggable") == "true" and not in_debug_sources:
            failures.append(f"{rel}: application android:debuggable=true outside debug sources")

        for tag in COMPONENT_TAGS:
            for comp in app.iter(tag):
                raw_name = comp.get(ANDROID + "name") or ""
                name = resolve_name(raw_name, package)
                exported = comp.get(ANDROID + "exported")
                has_filter = comp.find("intent-filter") is not None

                if has_filter and exported is None:
                    failures.append(
                        f"{rel}: <{tag} {raw_name}> has intent-filter but no "
                        "explicit android:exported"
                    )
                if exported == "true":
                    exported_seen.append(name)
                    if name not in EXPORTED_ALLOWLIST:
                        failures.append(
                            f"{rel}: <{tag} {raw_name}> is exported but not in "
                            "EXPORTED_ALLOWLIST — audit it, then allowlist"
                        )

        for tag in ("uses-permission", "uses-permission-sdk-23"):
            for perm in manifest.iter(tag):
                name = perm.get(ANDROID + "name") or ""
                if not name:
                    continue
                perms_seen.add(name)
                if name not in PERMISSION_ALLOWLIST:
                    failures.append(
                        f"{rel}: uses-permission '{name}' not in "
                        "PERMISSION_ALLOWLIST — justify it, then allowlist"
                    )

    for f in failures:
        print(f"manifest-audit: {f}", file=sys.stderr)
    if failures:
        print(f"manifest-audit: FAIL — {len(failures)} finding(s)", file=sys.stderr)
        return 1

    print(
        "manifest-audit: PASS — "
        f"{len(manifests)} manifest(s), {len(exported_seen)} exported component(s) "
        f"({', '.join(sorted(exported_seen)) or 'none'}), "
        f"{len(perms_seen)} permission(s) all allowlisted"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
