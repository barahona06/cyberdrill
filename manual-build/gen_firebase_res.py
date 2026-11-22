#!/usr/bin/env python3
"""
Generate the Firebase config string resources for the manual build.

The google-services Gradle plugin normally turns app/google-services.json
into res values (google_app_id, google_api_key, ...) that Firebase's
auto-initialization reads at runtime. The manual pipeline has no plugin, so
this script emits the same values as an overlay res directory.

Usage: gen_firebase_res.py <google-services.json> <out_values_xml>
"""
import json
import os
import sys


def main(gs_path, out_path):
    with open(gs_path, encoding="utf-8") as f:
        gs = json.load(f)
    info = gs.get("project_info", {})
    client = gs["client"][0]
    app_id = client["client_info"]["mobilesdk_app_id"]
    api_key = client["api_key"][0]["current_key"]
    values = {
        "google_app_id": app_id,
        "google_api_key": api_key,
        "gcm_defaultSenderId": info.get("project_number", ""),
        "project_id": info.get("project_id", ""),
        "google_storage_bucket": info.get("storage_bucket", ""),
    }
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n')
        for k, v in values.items():
            if v:
                f.write(f'    <string name="{k}" translatable="false">{v}</string>\n')
        f.write("</resources>\n")
    print(f"firebase config resources -> {out_path}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
