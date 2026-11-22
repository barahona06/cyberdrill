#!/usr/bin/env python3
"""
Merge the app AndroidManifest.xml with every library AAR manifest.

Gradle/AGP does this automatically; the manual pipeline needs it because
Firebase's initialization provider, the component-discovery service and the
ML Kit / App Check components are all declared in AAR manifests. Union rules:
app entries win on (tag, android:name) conflicts; permissions are unioned.

Usage: merge_manifests.py <app_manifest> <extracted_libs_dir> <output> [application_id]

Placeholder substitution: AAR manifests declare providers/permissions with
"${applicationId}" (e.g. FirebaseInitProvider's authority). AGP replaces it
with the app's applicationId during its merge; this script must do the same
or the APK installs but crashes/never opens on a real device. Pass the
applicationId as the 4th argument; any other "${...}" placeholder found is
reported on stderr and left untouched.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)


def akey(elem):
    return elem.get(f"{{{ANDROID}}}name")


PLACEHOLDER_RE = re.compile(r"\$\{([A-Za-z0-9_]+)\}")


def substitute_application_id(root, application_id):
    """Replace ${applicationId} in every attribute value and text node.

    Returns (substitutions, unresolved_placeholder_names).
    """
    count = 0
    unresolved = set()

    def fix(value):
        nonlocal count
        if value is None or "${" not in value:
            return value

        def repl(match):
            nonlocal count
            if match.group(1) == "applicationId" and application_id:
                count += 1
                return application_id
            unresolved.add(match.group(1))
            return match.group(0)

        return PLACEHOLDER_RE.sub(repl, value)

    for elem in root.iter():
        for attr in list(elem.attrib):
            elem.set(attr, fix(elem.get(attr)))
        elem.text = fix(elem.text)
        elem.tail = fix(elem.tail)
    return count, unresolved


def merge(app_manifest_path, libs_dir, out_path, application_id=None):
    app = ET.parse(app_manifest_path)
    root = app.getroot()

    def ensure(tag):
        e = root.find(tag)
        if e is None:
            e = ET.Element(tag)
            root.append(e)
        return e

    app_elem = ensure("application")
    seen_app_children = {}
    for child in app_elem:
        seen_app_children[(child.tag, akey(child))] = child
    seen_permissions = {
        (c.tag, akey(c)) for c in root if c.tag in ("uses-permission", "uses-permission-sdk-23")
    }

    merged_count = 0
    for name in sorted(os.listdir(libs_dir)):
        mpath = os.path.join(libs_dir, name, "AndroidManifest.xml")
        if not os.path.isfile(mpath):
            continue
        try:
            lib_root = ET.parse(mpath).getroot()
        except ET.ParseError:
            continue
        for child in lib_root:
            if child.tag in ("uses-permission", "uses-permission-sdk-23", "uses-feature"):
                key = (child.tag, akey(child))
                if key not in seen_permissions:
                    root.insert(list(root).index(app_elem), child)
                    seen_permissions.add(key)
            elif child.tag == "application":
                for comp in child:
                    key = (comp.tag, akey(comp))
                    if key not in seen_app_children:
                        app_elem.append(comp)
                        seen_app_children[key] = comp
                        merged_count += 1
                    else:
                        # Union meta-data children (e.g. component discovery).
                        existing = seen_app_children[key]
                        have = {
                            (c.tag, akey(c)) for c in existing
                        }
                        for sub in comp:
                            skey = (sub.tag, akey(sub))
                            sval = sub.get(f"{{{ANDROID}}}value")
                            if skey not in have or (
                                sub.tag == "meta-data"
                                and all(
                                    e.get(f"{{{ANDROID}}}value") != sval
                                    for e in existing.findall("meta-data")
                                    if akey(e) == akey(sub)
                                )
                            ):
                                existing.append(sub)
                                have.add(skey)
                                merged_count += 1
    sub_count, unresolved = substitute_application_id(root, application_id)
    tree = ET.ElementTree(root)
    tree.write(out_path, encoding="utf-8", xml_declaration=True)
    print(f"merged {merged_count} library components -> {out_path}")
    print(f"substituted {sub_count} ${{applicationId}} placeholders -> {application_id}")
    if unresolved:
        print(
            "WARNING: unresolved manifest placeholders left as-is: "
            + ", ".join(sorted(unresolved)),
            file=sys.stderr,
        )


if __name__ == "__main__":
    merge(
        sys.argv[1],
        sys.argv[2],
        sys.argv[3],
        sys.argv[4] if len(sys.argv) > 4 else None,
    )
