#!/usr/bin/env python3
"""Static metadata inspection for the packaged app APK. Requires androguard."""
import argparse
import hashlib
import json
import os
import struct
import zipfile
from androguard.core.apk import APK
from androguard.core.dex import DEX


def elf_machine(data):
    if len(data) < 20 or data[:4] != b"\x7fELF":
        return "not ELF"
    machine = struct.unpack_from("<H", data, 18)[0]
    return {40: "ARM", 62: "x86_64", 183: "AArch64"}.get(machine, f"e_machine={machine}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    args = parser.parse_args()
    path = os.path.abspath(args.apk)
    app = APK(path)
    with zipfile.ZipFile(path) as zf:
        infos = zf.infolist()
        dex_info = [x for x in infos if x.filename.endswith(".dex")]
        assets = [x for x in infos if x.filename.startswith("assets/")]
        native = [x for x in infos if x.filename.startswith("lib/") and x.filename.endswith(".so")]
        dex_counts = []
        for entry in dex_info:
            dex = DEX(zf.read(entry))
            dex_counts.append({
                "file": entry.filename,
                "classes": len(dex.get_classes()),
                "methods": len(dex.get_methods()),
                "strings": len(dex.get_strings()),
            })
        native_info = []
        for entry in native:
            native_info.append({"file": entry.filename, "size": entry.file_size,
                                "machine": elf_machine(zf.read(entry))})
        asset_info = [{"file": x.filename, "size": x.file_size, "compression": x.compress_type}
                      for x in assets]

    root = app.get_android_manifest_xml()
    android_ns = "{http://schemas.android.com/apk/res/android}"
    manifest = root if root.tag == "manifest" else root.find("manifest")
    application = root.find("application") if root.tag == "manifest" else root.find("./application")
    components = []
    if application is not None:
        for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
            for node in application.findall(tag):
                components.append({
                    "type": tag,
                    "name": node.get(android_ns + "name"),
                    "exported": node.get(android_ns + "exported"),
                    "permission": node.get(android_ns + "permission"),
                })
    sha256 = hashlib.sha256()
    with open(path, "rb") as apk_file:
        for chunk in iter(lambda: apk_file.read(1 << 20), b""):
            sha256.update(chunk)
    report = {
        "apk": os.path.basename(path),
        "sha256": sha256.hexdigest(),
        "file_size": os.path.getsize(path),
        "package": app.get_package(),
        "app_name": app.get_app_name(),
        "min_sdk": app.get_min_sdk_version(),
        "target_sdk": app.get_target_sdk_version(),
        "version_code": app.get_androidversion_code(),
        "version_name": app.get_androidversion_name(),
        "permissions": sorted(set(app.get_permissions())),
        "components": components,
        "signed_v1": app.is_signed_v1(),
        "signed_v2": app.is_signed_v2(),
        "signed_v3": app.is_signed_v3(),
        "dex": dex_counts,
        "native_libraries": native_info,
        "assets": asset_info,
        "asset_total_bytes": sum(x["size"] for x in asset_info),
    }
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
