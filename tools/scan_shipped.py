#!/usr/bin/env python3
"""Fail if test support or a test-only library ships (red team testing-build-13).

Usage: tools/scan_shipped.py [--apk APK ...] [--mapping mapping.txt ...]

* --apk: every classes*.dex in the APK is searched for type descriptors (Lpkg/...;) of the forbidden packages.
* --mapping: R8 mapping.txt class lines (`original.Name -> obfuscated:`) are checked for the same packages.
Exit 1 on any hit, 2 if a given file is missing. Prints package hit counts and up to 5 sample classes per package.
"""
import argparse, os, re, sys, zipfile

FORBIDDEN = [
    "dev/agentle/fakes/", "dev/agentle/core/testing/",
    "mockwebserver3/", "okhttp3/mockwebserver/",
    "kotlinx/coroutines/test/", "app/cash/turbine/",
    "org/robolectric/", "org/junit/", "junit/framework/", "junit/runner/",
    "androidx/test/",
]
DESCRIPTOR = re.compile(rb"L((?:" + b"|".join(re.escape(p.encode()) for p in FORBIDDEN) + rb")[\w/$\-]*);")


def scan_apk(path):
    hits = {}
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                for m in DESCRIPTOR.finditer(z.read(name)):
                    cls = m.group(1).decode("utf-8", "replace")
                    hits.setdefault(next(p for p in FORBIDDEN if cls.startswith(p)), set()).add(cls)
    return hits


def scan_mapping(path):
    hits = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line[:1] in ("#", " ", "\t") or " -> " not in line:
                continue
            cls = line.split(" -> ", 1)[0].strip().replace(".", "/")
            for p in FORBIDDEN:
                if cls.startswith(p):
                    hits.setdefault(p, set()).add(cls)
    return hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", action="append", default=[])
    ap.add_argument("--mapping", action="append", default=[])
    a = ap.parse_args()
    if not a.apk and not a.mapping:
        ap.error("give --apk and/or --mapping")
    failed = False
    for kind, paths, scan in (("APK", a.apk, scan_apk), ("mapping", a.mapping, scan_mapping)):
        for path in paths:
            if not os.path.isfile(path):
                print(f"ERROR: {kind} {path} not found", file=sys.stderr)
                return 2
            hits = scan(path)
            if not hits:
                print(f"OK {kind} {path}: no test support or test library classes")
                continue
            failed = True
            print(f"FAIL {kind} {path}:")
            for pkg, classes in sorted(hits.items()):
                sample = ", ".join(sorted(classes)[:5])
                print(f"  {pkg.replace('/', '.')}: {len(classes)} classes, e.g. {sample}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
