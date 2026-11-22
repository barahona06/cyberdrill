#!/usr/bin/env python3
"""
Drop jars that must not reach d8 together.

Two rules, both mirroring what Gradle's variant selection does:

1. KMP FAMILY RULE — Kotlin Multiplatform artifacts publish one variant per
   platform (root, -android, -jvm, ...). An Android build uses ONLY the
   -android variant; the siblings carry the same class names compiled for
   other targets. If a family has an -android member, drop every sibling
   that shares at least one class with it.

2. SUBSET RULE — drop a jar when EVERY class it contains is also provided
   by another jar in the list (KMP root redirects, legacy stubs). This can
   never remove a class from the build.

Artifact identity is read from the file name pattern used across this
project:  group__artifact__version__file  (for AAR classes.jar, the
extracted directory carries the pattern).

Usage: dedupe_jars.py <jar...>  -> prints the kept jars, one per line.
"""
import os
import re
import sys
import zipfile

PATTERN = re.compile(r"^(.*?)__(.*?)__([^_]+)__")
PLATFORM_SUFFIXES = ("-android", "-jvm", "-desktop", "-js", "-native", "-mingw", "-linux", "-macos", "-ios", "-tvos", "-watchos")


def classes_of(path):
    try:
        with zipfile.ZipFile(path) as z:
            return {n for n in z.namelist() if n.endswith(".class")}
    except Exception:  # noqa: BLE001
        return set()


def identity(path):
    """(group, artifact) from any path component matching the pattern."""
    for part in path.split(os.sep):
        m = PATTERN.match(part)
        if m:
            return m.group(1), m.group(2)
    return None, os.path.basename(path)


def family_of(artifact):
    for suf in PLATFORM_SUFFIXES:
        if artifact.endswith(suf):
            return artifact[: -len(suf)], suf
    return artifact, ""


def main(paths):
    sets = {p: classes_of(p) for p in paths}
    kept = list(paths)

    # Rule 1: KMP families — keep the -android variant, drop overlapping siblings.
    families = {}
    for p in kept:
        g, a = identity(p)
        if g is None:
            continue
        base, suf = family_of(a)
        families.setdefault((g, base), []).append((p, suf))
    for (g, base), members in families.items():
        android = [p for p, suf in members if suf == "-android"]
        if not android:
            continue
        android_classes = set()
        for p in android:
            android_classes |= sets[p]
        for p, suf in members:
            if suf != "-android" and sets[p] & android_classes:
                kept.remove(p)
                print(f"dedupe: dropped KMP sibling of {base}-android: {p}", file=sys.stderr)

    # Rule 2: subset — a jar fully covered by the others contributes nothing.
    changed = True
    while changed:
        changed = False
        for p in list(kept):
            others = set()
            for q in kept:
                if q != p:
                    others |= sets[q]
            if sets[p] and sets[p] <= others:
                kept.remove(p)
                print(f"dedupe: dropped fully-duplicated jar: {p}", file=sys.stderr)
                changed = True

    for p in kept:
        print(p)


if __name__ == "__main__":
    main(sys.argv[1:])
