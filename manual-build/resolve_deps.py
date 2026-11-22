#!/usr/bin/env python3
"""
CyberDrill manual-build dependency resolver.

The Gradle build (app/build.gradle.kts) is the canonical one, but this
workspace's sandbox blocks the raw localhost TCP that the Gradle daemon
protocol needs, so dependencies are resolved here with plain HTTPS instead:
a small Maven-style walker over Google Maven + Maven Central that downloads
the pinned graph into app/libs/ for build-manual.sh.

Semantics mirror Gradle's: for each (group, artifact) the HIGHEST requested
version wins, and the Compose BoM's dependencyManagement overrides every
transitive request. Iterated to a fixpoint because the chosen POMs decide
which requests exist.
"""
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
LIBS = os.path.join(PROJECT, "app", "libs")
CACHE = os.path.join(HERE, ".pom-cache")
REPOS = [
    "https://dl.google.com/dl/android/maven2",
    "https://repo.maven.apache.org/maven2",
]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

ROOTS = [
    ("androidx.core", "core-ktx", "1.13.1"),
    ("androidx.activity", "activity-compose", "1.9.3"),
    ("androidx.compose.ui", "ui", None),               # via BoM
    ("androidx.compose.ui", "ui-graphics", None),      # via BoM
    ("androidx.compose.ui", "ui-tooling-preview", None),  # via BoM
    ("androidx.compose.material3", "material3", None),  # via BoM
    ("androidx.lifecycle", "lifecycle-viewmodel-compose", "2.8.7"),
    ("androidx.lifecycle", "lifecycle-runtime-compose", "2.8.7"),
    ("androidx.navigation", "navigation-compose", "2.8.5"),
    ("androidx.datastore", "datastore-preferences", "1.1.1"),
    ("com.google.firebase", "firebase-ai", "17.17.0"),
    ("com.google.firebase", "firebase-ai-ondevice", "16.0.0-beta05"),
    ("com.google.firebase", "firebase-appcheck-debug", "19.4.1"),
    ("com.google.firebase", "firebase-appcheck-playintegrity", "19.4.1"),
    ("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.20"),
    # Alignment pins: some POMs (e.g. activity-ktx 1.9.3) still request
    # pre-split lifecycle artifacts at old versions whose classes moved into
    # the 2.8.7 artifacts. Gradle module metadata redirects these; a
    # POM-only walker must pin them explicitly, like a resolutionStrategy.
    ("androidx.lifecycle", "lifecycle-viewmodel-ktx", "2.8.7"),
    ("androidx.lifecycle", "lifecycle-livedata", "2.8.7"),
    ("androidx.lifecycle", "lifecycle-process", "2.8.7"),
    # Ktor variant pins (firebase-ai 17.17.0 -> io.ktor 3.0.3): the KMP root
    # artifacts are metadata-only redirects with ZERO classes; the classes
    # live in the -jvm variants, which Gradle selects via module metadata
    # (ktor 3.0.3 publishes no -android variant; the -jvm artifact serves
    # Android). A POM-only walker sees only the empty roots and their
    # dependency-free POMs, so without these pins the whole Ktor transport
    # (and transitives only the variant POMs declare: okhttp 4.12.0,
    # okhttp-sse, okio) never reaches the build -> NoClassDefFoundError
    # Lio/ktor/client/engine/okhttp/OkHttp at runtime. Pin every ktor
    # artifact in the graph, not just the engine: sibling variant POMs
    # depend on sibling ROOTS, so the variants are not pulled transitively.
    ("io.ktor", "ktor-client-core-jvm", "3.0.3"),
    ("io.ktor", "ktor-client-okhttp-jvm", "3.0.3"),
    ("io.ktor", "ktor-client-websockets-jvm", "3.0.3"),
    ("io.ktor", "ktor-client-content-negotiation-jvm", "3.0.3"),
    ("io.ktor", "ktor-client-logging-jvm", "3.0.3"),
    ("io.ktor", "ktor-serialization-kotlinx-json-jvm", "3.0.3"),
    ("io.ktor", "ktor-serialization-kotlinx-jvm", "3.0.3"),
    ("io.ktor", "ktor-serialization-jvm", "3.0.3"),
    ("io.ktor", "ktor-http-jvm", "3.0.3"),
    ("io.ktor", "ktor-io-jvm", "3.0.3"),
    ("io.ktor", "ktor-utils-jvm", "3.0.3"),
    ("io.ktor", "ktor-events-jvm", "3.0.3"),
    ("io.ktor", "ktor-websockets-jvm", "3.0.3"),
    ("io.ktor", "ktor-websocket-serialization-jvm", "3.0.3"),
    ("io.ktor", "ktor-sse-jvm", "3.0.3"),
]
BOM = ("androidx.compose", "compose-bom", "2024.12.01")
BOM_MANAGED = {}


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "cyberdrill-resolver"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def path_of(g, a, v, filename):
    return f"{g.replace('.', '/')}/{a}/{v}/{filename}"


def get_pom(g, a, v):
    os.makedirs(CACHE, exist_ok=True)
    cache_file = os.path.join(CACHE, f"{g}__{a}__{v}.pom")
    if os.path.exists(cache_file):
        with open(cache_file, "rb") as f:
            return ET.fromstring(f.read())
    last_err = None
    for repo in REPOS:
        url = f"{repo}/{path_of(g, a, v, f'{a}-{v}.pom')}"
        try:
            data = fetch(url)
            with open(cache_file, "wb") as f:
                f.write(data)
            return ET.fromstring(data)
        except Exception as e:  # noqa: BLE001
            last_err = e
    raise RuntimeError(f"POM not found for {g}:{a}:{v}: {last_err}")


def text(elem, tag):
    child = elem.find(f"m:{tag}", NS)
    return child.text.strip() if child is not None and child.text else None


def clean_version(v, props):
    if v is None:
        return None
    v = v.strip()
    for _ in range(5):
        m = re.fullmatch(r"\$\{(.+)\}", v)
        if not m:
            break
        v = props.get(m.group(1), v)
    if v.startswith("[") or v.startswith("("):
        v = v.strip("[]()").split(",")[0].strip()
    return v or None


def version_key(v):
    """Rough Gradle-ish ordering: numeric chunks, alpha < beta < rc < release."""
    tokens = re.split(r"[.\-_]", v)
    rank = {"alpha": 0, "beta": 1, "rc": 2, "cr": 2, "snapshot": 0}
    key = []
    for t in tokens:
        m = re.fullmatch(r"([a-zA-Z]*)(\d+)", t)
        if t.isdigit():
            key.append((1, int(t), 3, ""))
        elif m and m.group(1).lower() in rank:
            key.append((0, int(m.group(2)), rank[m.group(1).lower()], ""))
        elif m:
            key.append((1, int(m.group(2)), 3, m.group(1)))
        else:
            key.append((0, 0, rank.get(t.lower(), 3), t))
    return key


def collect_props(pom, props):
    p = pom.find("m:properties", NS)
    if p is not None:
        for child in p:
            tag = child.tag.split("}")[-1]
            props[tag] = (child.text or "").strip()
    props.setdefault("project.version", text(pom, "version") or "")
    props.setdefault("pom.version", props["project.version"])


def collect_managed(pom, managed, props):
    dm = pom.find("m:dependencyManagement/m:dependencies", NS)
    if dm is None:
        return
    for dep in dm.findall("m:dependency", NS):
        g, a = text(dep, "groupId"), text(dep, "artifactId")
        v = clean_version(text(dep, "version"), props)
        scope = text(dep, "scope")
        typ = text(dep, "type") or "jar"
        if scope == "import" and typ == "pom" and v:
            try:
                sub = get_pom(g, a, v)
                sub_props = dict(props)
                collect_props(sub, sub_props)
                collect_managed(sub, managed, sub_props)
            except Exception as e:  # noqa: BLE001
                print(f"  ! BOM import failed {g}:{a}:{v}: {e}", file=sys.stderr)
        elif g and a and v:
            managed[(g, a)] = v


def parent_of(pom):
    p = pom.find("m:parent", NS)
    if p is None:
        return None
    return text(p, "groupId"), text(p, "artifactId"), text(p, "version")


_POM_INFO_CACHE = {}


def pom_info(g, a, v):
    if (g, a, v) in _POM_INFO_CACHE:
        return _POM_INFO_CACHE[(g, a, v)]
    pom = get_pom(g, a, v)
    props = {}
    managed = {}
    chain = [pom]
    parent = parent_of(pom)
    seen_parents = set()
    while parent and parent not in seen_parents and len(chain) < 6:
        seen_parents.add(parent)
        try:
            ppom = get_pom(*parent)
        except Exception:  # noqa: BLE001
            break
        chain.append(ppom)
        parent = parent_of(ppom)
    for p in reversed(chain):
        collect_props(p, props)
    props["project.version"] = v
    props["pom.version"] = v
    for p in reversed(chain):
        collect_managed(p, managed, props)
    packaging = text(pom, "packaging") or "jar"
    deps = []
    deps_elem = pom.find("m:dependencies", NS)
    if deps_elem is not None:
        for dep in deps_elem.findall("m:dependency", NS):
            dg, da = text(dep, "groupId"), text(dep, "artifactId")
            if not dg or not da:
                continue
            scope = text(dep, "scope") or "compile"
            optional = text(dep, "optional") == "true"
            if scope in ("test", "provided", "system", "import") or optional:
                continue
            dv = clean_version(text(dep, "version"), props)
            if dv is None:
                dv = managed.get((dg, da)) or BOM_MANAGED.get((dg, da))
            if dv is None:
                print(f"  ! no version for {dg}:{da} (dep of {g}:{a})", file=sys.stderr)
                continue
            deps.append((dg, da, dv))
    _POM_INFO_CACHE[(g, a, v)] = (packaging, deps)
    return packaging, deps


def choose(requests):
    """requests: (g,a) -> set of versions. BoM wins; else highest."""
    chosen = {}
    for (g, a), versions in requests.items():
        if (g, a) in BOM_MANAGED:
            chosen[(g, a)] = BOM_MANAGED[(g, a)]
        else:
            chosen[(g, a)] = max(versions, key=version_key)
    return chosen


def main():
    os.makedirs(LIBS, exist_ok=True)
    bg, ba, bv = BOM
    bpom = get_pom(bg, ba, bv)
    bprops = {}
    collect_props(bpom, bprops)
    collect_managed(bpom, BOM_MANAGED, bprops)
    print(f"BoM {bg}:{ba}:{bv} manages {len(BOM_MANAGED)} artifacts")

    chosen = {}
    for iteration in range(8):
        requests = {}
        def request(g, a, v):
            if (g, a) in BOM_MANAGED:
                v = BOM_MANAGED[(g, a)]
            requests.setdefault((g, a), set()).add(v)
        for g, a, v in ROOTS:
            request(g, a, v or BOM_MANAGED.get((g, a)))
        for (g, a), v in list(chosen.items()):
            try:
                _, deps = pom_info(g, a, v)
            except Exception as e:  # noqa: BLE001
                print(f"  ! POM failed {g}:{a}:{v}: {e}", file=sys.stderr)
                continue
            for dg, da, dv in deps:
                request(dg, da, dv)
        new_chosen = choose(requests)
        if new_chosen == chosen:
            print(f"Fixpoint after {iteration + 1} iterations: {len(chosen)} artifacts")
            break
        chosen = new_chosen
    else:
        print("No fixpoint; using last resolution", file=sys.stderr)

    # Remove stale downloads that are no longer chosen.
    wanted_files = set()
    failures = []
    for (g, a), v in sorted(chosen.items()):
        try:
            packaging, _ = pom_info(g, a, v)
        except Exception:  # noqa: BLE001
            packaging = "jar"
        ext = "aar" if packaging == "aar" else "jar"
        fname = f"{g}__{a}__{v}__{a}-{v}.{ext}"
        wanted_files.add(fname)
        dest = os.path.join(LIBS, fname)
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            continue
        ok = False
        for repo in REPOS:
            try:
                data = fetch(f"{repo}/{path_of(g, a, v, f'{a}-{v}.{ext}')}")
                with open(dest, "wb") as fh:
                    fh.write(data)
                ok = True
                break
            except Exception:  # noqa: BLE001
                continue
        if not ok:
            alt = "jar" if ext == "aar" else "aar"
            altname = f"{g}__{a}__{v}__{a}-{v}.{alt}"
            for repo in REPOS:
                try:
                    data = fetch(f"{repo}/{path_of(g, a, v, f'{a}-{v}.{alt}')}")
                    with open(os.path.join(LIBS, altname), "wb") as fh:
                        fh.write(data)
                    wanted_files.add(altname)
                    wanted_files.discard(fname)
                    ok = True
                    break
                except Exception:  # noqa: BLE001
                    continue
        if not ok:
            failures.append(f"{g}:{a}:{v}")
    for old in os.listdir(LIBS):
        if old not in wanted_files:
            os.remove(os.path.join(LIBS, old))
    if failures:
        print("FAILED downloads:", *failures, sep="\n  ", file=sys.stderr)
        sys.exit(2)
    print(f"All {len(wanted_files)} artifacts in app/libs/")


if __name__ == "__main__":
    main()
