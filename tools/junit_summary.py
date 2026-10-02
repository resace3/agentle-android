#!/usr/bin/env python3
"""Aggregate JUnit XML (and optional Kover XML / db-scale JSON) into an evidence report with EXACT counts.

Usage:
  tools/junit_summary.py [--root DIR ...] [--expect SUITE ...] [--fresh-after ISO8601] [--json OUT.json] [--markdown OUT.md]

Rules (so a report can never invent numbers):
  * Every number printed is computed from files found on disk; each file is listed with its SHA-256.
  * Exit 1 if any test failed/errored, if NO JUnit XML was found, or if an --expect'ed suite is missing.
  * --fresh-after: exit 1 if a suite's newest <testsuite timestamp> is older than this instant, i.e. its XML was
    restored from the build cache or left over from an earlier build (Gradle: FROM-CACHE / UP-TO-DATE).
  * Robolectric tests in Android unit-test tasks (test<Variant>UnitTest) are split per SDK using the "[api]" suffix
    Robolectric appends to test names; run with -Drobolectric.alwaysIncludeVariantMarkersInTestName=true so the
    newest SDK is suffixed too. Tests without a suffix are counted under "jvm".
"""
import argparse, glob, hashlib, json, os, re, subprocess, sys, datetime
import xml.etree.ElementTree as ET

SDK_SUFFIX = re.compile(r"\[(\d{2})\]$")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def suite_key(path, root):
    rel = os.path.relpath(path, root).replace(os.sep, "/")
    if "/build/test-results/" in rel:
        module, rest = rel.split("/build/test-results/", 1)
        task = rest.split("/", 1)[0]
        kind = "jvm-or-robolectric"
    elif "/build/outputs/androidTest-results/" in rel:
        module, rest = rel.split("/build/outputs/androidTest-results/", 1)
        task = "androidTest/" + os.path.dirname(rest)
        kind = "instrumented"
    else:
        module, task, kind = os.path.dirname(rel), "unknown", "unknown"
    module = ":" + module.strip("./").replace("/", ":") if module not in (".", "") else ":"
    return f"{module}:{task}", task, kind


def parse_timestamps(path):
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else root.findall(".//testsuite")
    out = []
    for s in suites:
        ts = s.get("timestamp")
        if ts:
            t = datetime.datetime.fromisoformat(ts.replace("Z", "+00:00"))
            out.append(t if t.tzinfo else t.replace(tzinfo=datetime.timezone.utc))
    return out


def parse_cases(path):
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else root.findall(".//testsuite")
    for s in suites:
        for tc in s.findall("testcase"):
            name, cls = tc.get("name", ""), tc.get("classname", "")
            t = float(tc.get("time", "0") or 0)
            if tc.find("failure") is not None:
                status, node = "failed", tc.find("failure")
            elif tc.find("error") is not None:
                status, node = "error", tc.find("error")
            elif tc.find("skipped") is not None:
                status, node = "skipped", None
            else:
                status, node = "passed", None
            msg = ((node.get("message") or node.text or "").strip().splitlines() or [""])[0][:200] if node is not None else ""
            yield cls, name, status, t, msg


def coverage(roots):
    out = {}
    for root in roots:
        for path in glob.glob(os.path.join(root, "**/build/reports/kover/report*.xml"), recursive=True):
            rep = os.path.relpath(path, root)
            pkgs = {}
            for pkg in ET.parse(path).getroot().findall("package"):
                c = {x.get("type"): (int(x.get("missed")), int(x.get("covered"))) for x in pkg.findall("counter")}
                def pct(kind):
                    m, cv = c.get(kind, (0, 0))
                    return None if m + cv == 0 else round(100.0 * cv / (m + cv), 2)
                pkgs[pkg.get("name").replace("/", ".")] = {"line": pct("LINE"), "branch": pct("BRANCH"), "instruction": pct("INSTRUCTION")}
            out[rep] = {"sha256": sha256(path), "packages": pkgs}
    return out


def db_scale(roots):
    res = []
    for root in roots:
        for path in sorted(glob.glob(os.path.join(root, "**/build/db-scale/results-*.json"), recursive=True)):
            with open(path) as f:
                d = json.load(f)
            d["file"] = os.path.relpath(path, root)
            res.append(d)
    return sorted(res, key=lambda d: d.get("size", 0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", action="append", default=None)
    ap.add_argument("--expect", action="append", default=[], help="suite key that must be present, e.g. :core:jitai:test")
    ap.add_argument("--fresh-after", help="ISO-8601 instant; suites whose XML is older are reported as stale (exit 1)")
    ap.add_argument("--json")
    ap.add_argument("--markdown")
    a = ap.parse_args()
    roots = a.root or ["."]
    patterns = ["**/build/test-results/*/TEST-*.xml", "**/build/test-results/*/*/TEST-*.xml",
                "**/build/outputs/androidTest-results/**/TEST-*.xml"]
    files = sorted({p for r in roots for pat in patterns for p in glob.glob(os.path.join(r, pat), recursive=True)})
    suites, failures, file_list = {}, [], []
    for path in files:
        root = next(r for r in roots if os.path.abspath(path).startswith(os.path.abspath(r)))
        key, task, kind = suite_key(path, root)
        file_list.append({"file": os.path.relpath(path, root), "sha256": sha256(path)})
        is_android_unit = bool(re.match(r"test\w*UnitTest$", task))
        stamps = parse_timestamps(path)
        for cls, name, status, t, msg in parse_cases(path):
            sdk = "jvm"
            if is_android_unit:
                m = SDK_SUFFIX.search(name)
                if m:
                    sdk = "sdk" + m.group(1)
            s = suites.setdefault(key, {"kind": kind, "by_sdk": {}, "tests": 0, "passed": 0, "failed": 0, "error": 0, "skipped": 0, "time_s": 0.0, "_ts": []})
            b = s["by_sdk"].setdefault(sdk, {"tests": 0, "passed": 0, "failed": 0, "error": 0, "skipped": 0})
            for d in (s, b):
                d["tests"] += 1
                d[status] += 1
            s["time_s"] = round(s["time_s"] + t, 3)
            if status in ("failed", "error"):
                failures.append({"suite": key, "test": f"{cls}.{name}", "status": status, "message": msg})
        if key in suites:
            suites[key]["_ts"].extend(stamps)
    fresh_after = None
    if a.fresh_after:
        fresh_after = datetime.datetime.fromisoformat(a.fresh_after.replace("Z", "+00:00"))
        if fresh_after.tzinfo is None:
            fresh_after = fresh_after.replace(tzinfo=datetime.timezone.utc)
    stale = []
    for key, s in suites.items():
        ts = s.pop("_ts")
        s["firstStartedAt"] = min(ts).isoformat(timespec="seconds") if ts else None
        s["lastStartedAt"] = max(ts).isoformat(timespec="seconds") if ts else None
        if fresh_after and (not ts or min(ts) < fresh_after):
            stale.append(key)
    totals = {k: sum(s[k] for s in suites.values()) for k in ("tests", "passed", "failed", "error", "skipped")}
    try:
        commit = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, cwd=roots[0]).stdout.strip() or None
    except OSError:
        commit = None
    missing = [e for e in a.expect if e not in suites]
    report = {
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "gitCommit": commit, "roots": [os.path.abspath(r) for r in roots],
        "totals": totals, "suites": dict(sorted(suites.items())), "failures": failures,
        "missingExpectedSuites": missing, "freshAfter": a.fresh_after, "staleSuites": sorted(stale), "files": file_list,
        "coverage": coverage(roots), "dbScale": db_scale(roots),
    }
    lines = [f"# Test evidence ({report['generatedAt']}, commit {commit or 'n/a'})", "",
             "| Suite | Kind | SDK | Tests | Passed | Failed | Errors | Skipped | Ran (UTC) |", "|---|---|---|---:|---:|---:|---:|---:|---|"]
    for key, s in report["suites"].items():
        for sdk, b in sorted(s["by_sdk"].items()):
            ran = (s["lastStartedAt"] or "?").replace("+00:00", "Z") + (" STALE" if key in stale else "")
            lines.append(f"| `{key}` | {s['kind']} | {sdk} | {b['tests']} | {b['passed']} | {b['failed']} | {b['error']} | {b['skipped']} | {ran} |")
    lines.append(f"| **TOTAL** | | | **{totals['tests']}** | **{totals['passed']}** | **{totals['failed']}** | **{totals['error']}** | **{totals['skipped']}** | |")
    if failures:
        lines += ["", "## Failures", ""] + [f"- `{f['test']}` ({f['suite']}, {f['status']}): {f['message']}" for f in failures]
    for rep, c in report["coverage"].items():
        lines += ["", f"## Coverage: `{rep}`", "", "| Package | Line % | Branch % |", "|---|---:|---:|"]
        lines += [f"| `{p}` | {v['line']} | {v['branch']} |" for p, v in sorted(c["packages"].items())]
    if report["dbScale"]:
        lines += ["", "## DB scale (JVM, sqlite-jdbc)", "", "| Events | SQLite | Insert ms | Events/s | Re-ingest 10% ms | 7-day steps ms | Latest HR ms | Full scan ms | DB MiB |",
                  "|---:|---|---:|---:|---:|---:|---:|---:|---:|"]
        for d in report["dbScale"]:
            q = d.get("queryMedianMs", {})
            lines.append(f"| {d['size']} | {d.get('sqliteVersion')} | {d.get('insertMs')} | {d.get('insertEventsPerSecond')} | {d.get('reingest10PercentMs')} | "
                         f"{q.get('dailySteps7d')} | {q.get('latestHeartRate')} | {q.get('fullScanGroupByType')} | {round(d.get('dbFileBytes', 0) / 1048576, 1)} |")
    if missing:
        lines += ["", "## Missing expected suites", ""] + [f"- `{m}`" for m in missing]
    if stale:
        lines += ["", f"## Stale suites (results older than {a.fresh_after})", ""] + [f"- `{m}`" for m in sorted(stale)]
    lines += ["", f"Source files: {len(file_list)} JUnit XML file(s); SHA-256 of each in the JSON report."]
    md = "\n".join(lines) + "\n"
    if a.markdown:
        with open(a.markdown, "w") as f:
            f.write(md)
    if a.json:
        with open(a.json, "w") as f:
            json.dump(report, f, indent=2)
    print(md)
    if not files:
        print("ERROR: no JUnit XML found; refusing to report success.", file=sys.stderr)
        return 1
    return 1 if (failures or missing or stale) else 0


if __name__ == "__main__":
    sys.exit(main())
