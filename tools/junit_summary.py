#!/usr/bin/env python3
"""Aggregate JUnit XML (and optional Kover XML / db-scale JSON) into an evidence report with EXACT counts.

Usage:
  tools/junit_summary.py [--root DIR ...] [--expect SUITE ...] [--expect-file FILE] [--fresh-after ISO8601]
                         [--skipped-allowlist FILE] [--coverage-gates FILE] [--json OUT.json] [--markdown OUT.md]

Rules (so a report can never invent numbers):
  * Every number printed is computed from files found on disk; each file is listed with its SHA-256.
  * Exit 1 if any test failed/errored, if NO JUnit XML was found, or if an --expect'ed suite is missing.
  * --expect-file (written by `./gradlew writeExpectedSuites`): one `<suite> [minimum]` per line. Exit 1 if a suite
    with a minimum of 1 or more is missing, or ran fewer distinct tests than its minimum. Minimum 0: may be absent.
    Its `# mode:` header (full or jvm-only) is the report's build mode.
  * --fresh-after: exit 1 if a suite's newest <testsuite timestamp> is older than this instant, i.e. its XML was
    restored from the build cache or left over from an earlier build (Gradle: FROM-CACHE / UP-TO-DATE).
  * Skipped tests: exit 1 for any skipped test that tools/skipped-allowlist.txt (or --skipped-allowlist) does not
    list with a reason. A skipped test did not run.
  * Distinct tests count each test once, whatever the number of Robolectric SDKs that ran it; minimums use them.
  * Robolectric tests in Android unit-test tasks (test<Variant>UnitTest) are split per SDK using the "[api]" suffix
    Robolectric appends to test names; run with -Drobolectric.alwaysIncludeVariantMarkersInTestName=true so the
    newest SDK is suffixed too. Tests without a suffix are counted under "jvm".
  * Coverage: every Kover XML report (<module>/build/reports/kover/report[<Variant>].xml) is listed with its line and
    branch totals; :fakes and :core:testing never count. --coverage-gates (written by `./gradlew writeCoverageGates`
    from build-logic CoverageGates.kt) adds each gate and its status. Its `# enforce:` header mirrors
    -Pagentle.coverage.enforce: when true, exit 1 for a gate below its minimum, without a fresh report, or whose
    report measured no line (a stale class pattern); when false, gate misses are reported as warnings only.
"""
import argparse, glob, hashlib, json, os, re, subprocess, sys, datetime
import xml.etree.ElementTree as ET

SDK_SUFFIX = re.compile(r"\[(\d{2})\]$")
DEFAULT_ALLOWLIST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "skipped-allowlist.txt")
NEVER_COUNTED = {":fakes", ":core:testing"}  # test support (build-logic CoverageGates.neverCounted)
KOVER_DIR = "build/reports/kover/"


def read_expect_file(path):
    """(mode, kind, {suite: minimum}) from a :writeExpectedSuites file; exits on a malformed line."""
    mode, kind, expected = None, None, {}
    if not os.path.exists(path):
        sys.exit(f"{path} not found: run ./gradlew writeExpectedSuites first")
    with open(path) as f:
        for n, raw in enumerate(f, 1):
            line = raw.strip()
            header = re.match(r"#\s*(mode|kind):\s*(\S+)", line)
            if header:
                if header.group(1) == "mode":
                    mode = header.group(2)
                else:
                    kind = header.group(2)
                continue
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) > 2 or (len(parts) == 2 and not parts[1].isdigit()):
                sys.exit(f"{path}:{n}: expected '<suite> [minimum]'")
            expected[parts[0]] = int(parts[1]) if len(parts) == 2 else 1
    return mode, kind, expected


def read_allowlist(path):
    """({(class, test name): reason}, [errors]) from a skipped-test allow-list; a missing file allows nothing."""
    entries, errors = {}, []
    if not path or not os.path.exists(path):
        return entries, errors
    with open(path) as f:
        for n, raw in enumerate(f, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            test, sep, reason = line.partition(" | ")
            cls, hash_sign, name = test.strip().partition("#")
            if not sep or not reason.strip() or not hash_sign or not cls or not name:
                errors.append(f"{os.path.basename(path)} line {n}: expected '<Class>#<test name> | <reason>'")
                continue
            entries[(cls, name)] = reason.strip()
    return entries, errors


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


def read_coverage_gates(path):
    """(enforce, {(module, report): [gate]}) from a :writeCoverageGates file; exits on a malformed line."""
    enforce, gates = None, {}
    if not os.path.exists(path):
        sys.exit(f"{path} not found: run ./gradlew writeCoverageGates first")
    with open(path) as f:
        for n, raw in enumerate(f, 1):
            line = raw.strip()
            header = re.match(r"#\s*enforce:\s*(true|false)\s*$", line)
            if header:
                enforce = header.group(1) == "true"
                continue
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) != 5 or not parts[3].isdigit() or not (parts[4] == "-" or parts[4].isdigit()):
                sys.exit(f"{path}:{n}: expected '<module> <report> <area> <line minimum> <branch minimum or ->'")
            gates.setdefault((parts[0], parts[1]), []).append(
                {"area": parts[2], "line": int(parts[3]), "branch": None if parts[4] == "-" else int(parts[4])})
    if enforce is None:
        sys.exit(f"{path}: missing '# enforce: true|false' header")
    return enforce, gates


def kover_report_id(rel):
    """(module path, report) for a Kover XML report path relative to the root: report.xml is `total`,
    report<Variant>.xml the Kover variant, e.g. core/network/build/reports/kover/reportOauth.xml -> (':core:network', 'oauth')."""
    module_dir, name = rel.rsplit(KOVER_DIR, 1)
    module = ":" + module_dir.strip("/").replace("/", ":") if module_dir.strip("/") else ":"
    variant = name[len("report"):-len(".xml")]
    return module, (variant[:1].lower() + variant[1:]) if variant else "total"


def pct(counts):
    missed, covered = counts
    return None if missed + covered == 0 else round(100.0 * covered / (missed + covered), 2)


def coverage(roots, fresh_after=None):
    """Kover XML reports by path: module, report, root-level line/branch totals, per-package numbers, freshness."""
    out = {}
    for root in roots:
        for path in sorted(glob.glob(os.path.join(root, "**/" + KOVER_DIR + "report*.xml"), recursive=True)):
            rep = os.path.relpath(path, root).replace(os.sep, "/")
            module, report = kover_report_id(rep)
            if module in NEVER_COUNTED:
                continue
            xml_root = ET.parse(path).getroot()
            totals = {x.get("type"): (int(x.get("missed")), int(x.get("covered"))) for x in xml_root.findall("counter")}
            pkgs = {}
            for pkg in xml_root.findall("package"):
                c = {x.get("type"): (int(x.get("missed")), int(x.get("covered"))) for x in pkg.findall("counter")}
                pkgs[pkg.get("name").replace("/", ".")] = {
                    "line": pct(c.get("LINE", (0, 0))), "branch": pct(c.get("BRANCH", (0, 0))),
                    "instruction": pct(c.get("INSTRUCTION", (0, 0)))}
            modified = datetime.datetime.fromtimestamp(os.path.getmtime(path), datetime.timezone.utc)
            line, branch = totals.get("LINE", (0, 0)), totals.get("BRANCH", (0, 0))
            out[rep] = {
                "module": module, "report": report, "sha256": sha256(path),
                "line": pct(line), "lines": {"missed": line[0], "covered": line[1]},
                "branch": pct(branch), "branches": {"missed": branch[0], "covered": branch[1]},
                "writtenAt": modified.isoformat(timespec="seconds"),
                "stale": bool(fresh_after and modified < fresh_after), "packages": pkgs}
    return out


def gate_status(gates, report):
    """Status of one report against its gates: ok, BELOW, EMPTY (no line measured), NO REPORT or STALE."""
    if report is None:
        return "NO REPORT", []
    if report["stale"]:
        return "STALE", []
    if report["lines"]["missed"] + report["lines"]["covered"] == 0:
        return "EMPTY", []
    misses = []
    for g in gates:
        if report["line"] < g["line"]:
            misses.append(f"{g['area']} line {report['line']} < {g['line']}")
        if g["branch"] is not None and report["branch"] is not None and report["branch"] < g["branch"]:
            misses.append(f"{g['area']} branch {report['branch']} < {g['branch']}")
    return ("BELOW" if misses else "ok"), misses


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
    ap.add_argument("--expect-file", help="file from `./gradlew writeExpectedSuites`: `<suite> [minimum]` per line")
    ap.add_argument("--fresh-after", help="ISO-8601 instant; suites whose XML is older are reported as stale (exit 1)")
    ap.add_argument("--skipped-allowlist", default=DEFAULT_ALLOWLIST,
                    help="skipped tests allowed to stay skipped, `<Class>#<test name> | <reason>` per line")
    ap.add_argument("--coverage-gates", help="file from `./gradlew writeCoverageGates`: the Kover gates to check")
    ap.add_argument("--json")
    ap.add_argument("--markdown")
    a = ap.parse_args()
    roots = a.root or ["."]
    patterns = ["**/build/test-results/*/TEST-*.xml", "**/build/test-results/*/*/TEST-*.xml",
                "**/build/outputs/androidTest-results/**/TEST-*.xml"]
    files = sorted({p for r in roots for pat in patterns for p in glob.glob(os.path.join(r, pat), recursive=True)})
    suites, failures, file_list, skipped = {}, [], [], []
    for path in files:
        root = next(r for r in roots if os.path.abspath(path).startswith(os.path.abspath(r)))
        key, task, kind = suite_key(path, root)
        file_list.append({"file": os.path.relpath(path, root), "sha256": sha256(path)})
        is_android_unit = bool(re.match(r"test\w*UnitTest$", task))
        stamps = parse_timestamps(path)
        for cls, name, status, t, msg in parse_cases(path):
            sdk, base_name = "jvm", name
            if is_android_unit:
                m = SDK_SUFFIX.search(name)
                if m:
                    sdk = "sdk" + m.group(1)
                    base_name = name[:m.start()]
            s = suites.setdefault(key, {"kind": kind, "by_sdk": {}, "tests": 0, "passed": 0, "failed": 0, "error": 0, "skipped": 0, "time_s": 0.0, "_ts": [], "_distinct": set()})
            s["_distinct"].add((cls, base_name))
            if status == "skipped":
                skipped.append({"suite": key, "class": cls, "name": base_name, "sdk": sdk})
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
        s["distinct"] = len(s.pop("_distinct"))
        ts = s.pop("_ts")
        s["firstStartedAt"] = min(ts).isoformat(timespec="seconds") if ts else None
        s["lastStartedAt"] = max(ts).isoformat(timespec="seconds") if ts else None
        if fresh_after and (not ts or min(ts) < fresh_after):
            stale.append(key)
    totals = {k: sum(s[k] for s in suites.values()) for k in ("tests", "distinct", "passed", "failed", "error", "skipped")}
    try:
        commit = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, cwd=roots[0]).stdout.strip() or None
    except OSError:
        commit = None
    expected, mode, expect_kind = {}, None, None
    if a.expect_file:
        mode, expect_kind, expected = read_expect_file(a.expect_file)
    for e in a.expect:
        expected[e] = max(expected.get(e, 1), 1)
    if mode is None:
        mode = "jvm-only" if os.environ.get("AGENTLE_JVM_ONLY") == "true" else "unknown"
    missing = sorted(e for e, minimum in expected.items() if minimum > 0 and e not in suites)
    below = [{"suite": e, "distinct": suites[e]["distinct"], "minimum": minimum}
             for e, minimum in sorted(expected.items()) if e in suites and suites[e]["distinct"] < minimum]
    allowlist, allowlist_errors = read_allowlist(a.skipped_allowlist)
    skipped_allowed, skipped_not_allowed = [], []
    for sk in skipped:
        reason = allowlist.get((sk["class"], sk["name"])) or allowlist.get((sk["class"], "*"))
        (skipped_allowed if reason else skipped_not_allowed).append(dict(sk, reason=reason))
    cov = coverage(roots, fresh_after)
    enforce, gates = read_coverage_gates(a.coverage_gates) if a.coverage_gates else (False, {})
    by_id = {(c["module"], c["report"]): c for c in cov.values()}
    gate_rows = []
    for key in sorted(set(gates) | set(by_id)):
        status, misses = gate_status(gates[key], by_id.get(key)) if key in gates else ("-", [])
        gate_rows.append({"module": key[0], "report": key[1], "gates": gates.get(key, []), "status": status, "misses": misses})
    gate_failures = [r for r in gate_rows if r["status"] not in ("ok", "-")] if enforce else []
    report = {
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "gitCommit": commit, "buildMode": mode, "roots": [os.path.abspath(r) for r in roots],
        "expectFile": ({"file": a.expect_file, "sha256": sha256(a.expect_file), "kind": expect_kind}
                       if a.expect_file else None),
        "totals": totals, "suites": dict(sorted(suites.items())), "failures": failures,
        "expectedSuites": dict(sorted(expected.items())),
        "missingExpectedSuites": missing, "belowMinimum": below, "freshAfter": a.fresh_after, "staleSuites": sorted(stale),
        "skippedNotAllowed": skipped_not_allowed, "skippedAllowed": skipped_allowed, "allowlistErrors": allowlist_errors,
        "files": file_list, "coverage": cov, "dbScale": db_scale(roots),
        "coverageGates": ({"file": a.coverage_gates, "sha256": sha256(a.coverage_gates), "enforce": enforce,
                           "rows": gate_rows, "failures": len(gate_failures)} if a.coverage_gates else None),
    }
    lines = [f"# Test evidence ({report['generatedAt']}, commit {commit or 'n/a'}, build mode {mode})", "",
             "| Suite | Kind | SDK | Tests | Passed | Failed | Errors | Skipped | Ran (UTC) |", "|---|---|---|---:|---:|---:|---:|---:|---|"]
    for key, s in report["suites"].items():
        for sdk, b in sorted(s["by_sdk"].items()):
            ran = (s["lastStartedAt"] or "?").replace("+00:00", "Z") + (" STALE" if key in stale else "")
            lines.append(f"| `{key}` | {s['kind']} | {sdk} | {b['tests']} | {b['passed']} | {b['failed']} | {b['error']} | {b['skipped']} | {ran} |")
    lines.append(f"| **TOTAL** | | | **{totals['tests']}** | **{totals['passed']}** | **{totals['failed']}** | **{totals['error']}** | **{totals['skipped']}** | |")
    lines += ["", f"Distinct tests: **{totals['distinct']}** (a test run on several Robolectric SDKs counts once)."]
    if expected:
        lines += ["", f"## Expected suites ({a.expect_file or '--expect'})", "", "| Suite | Minimum | Distinct | Status |", "|---|---:|---:|---|"]
        for e, minimum in sorted(expected.items()):
            if e in suites:
                status = "BELOW MINIMUM" if suites[e]["distinct"] < minimum else "ok"
                lines.append(f"| `{e}` | {minimum} | {suites[e]['distinct']} | {status} |")
            else:
                lines.append(f"| `{e}` | {minimum} | - | {'MISSING' if minimum > 0 else 'no tests yet'} |")
    if failures:
        lines += ["", "## Failures", ""] + [f"- `{f['test']}` ({f['suite']}, {f['status']}): {f['message']}" for f in failures]
    if gate_rows:
        def counted(c, kind, unit):
            n = c[unit]
            return f"{c[kind] if c[kind] is not None else '-'} ({n['covered']}/{n['missed'] + n['covered']})"
        mode_note = ("enforced: a gate below its minimum fails the build (koverVerify) and any gate that is not ok "
                     "fails this report" if enforce else
                     "not enforced: misses are warnings (-Pagentle.coverage.enforce=true makes them fail)")
        lines += ["", "## Coverage (Kover)", "",
                  (f"Gates from `{a.coverage_gates}` (build-logic CoverageGates.kt), {mode_note}. " if a.coverage_gates
                   else "No --coverage-gates file: numbers only. ") +
                  "Line and branch %: covered/total in brackets; per-package numbers are in the JSON report.", "",
                  "| Module | Report | Line % | Branch % | Gate | Status |", "|---|---|---:|---:|---|---|"]
        for r in gate_rows:
            c = by_id.get((r["module"], r["report"]))
            gate = "; ".join(f"{g['area']}: line >= {g['line']}" + (f", branch >= {g['branch']}" if g["branch"] is not None else "")
                             for g in r["gates"]) or "-"
            status = r["status"] + ("" if enforce or r["status"] in ("ok", "-") else " (warning)")
            if r["misses"]:
                status += ": " + "; ".join(r["misses"])
            line_cell = counted(c, "line", "lines") if c else "-"
            branch_cell = counted(c, "branch", "branches") if c else "-"
            lines.append(f"| `{r['module']}` | {r['report']} | {line_cell} | {branch_cell} | {gate} | {status} |")
        # Merged: each module counted once, from its whole-module report (JVM total, Android debug / app prodDebug).
        whole = [c for c in cov.values() if c["module"] != ":" and c["report"] in ("total", "debug", "prodDebug") and not c["stale"]]
        if whole:
            merged = {u: {k: sum(c[u][k] for c in whole) for k in ("missed", "covered")} for u in ("lines", "branches")}
            merged.update(line=pct((merged["lines"]["missed"], merged["lines"]["covered"])),
                          branch=pct((merged["branches"]["missed"], merged["branches"]["covered"])))
            report["coverageMerged"] = dict(merged, modules=len(whole))
            lines.append(f"| **merged ({len(whole)} modules)** | - | {counted(merged, 'line', 'lines')} | "
                         f"{counted(merged, 'branch', 'branches')} | - | - |")
    if report["dbScale"]:
        lines += ["", "## DB scale (JVM, sqlite-jdbc)", "", "| Events | SQLite | Insert ms | Events/s | Re-ingest 10% ms | 7-day steps ms | Latest HR ms | Full scan ms | DB MiB |",
                  "|---:|---|---:|---:|---:|---:|---:|---:|---:|"]
        for d in report["dbScale"]:
            q = d.get("queryMedianMs", {})
            lines.append(f"| {d['size']} | {d.get('sqliteVersion')} | {d.get('insertMs')} | {d.get('insertEventsPerSecond')} | {d.get('reingest10PercentMs')} | "
                         f"{q.get('dailySteps7d')} | {q.get('latestHeartRate')} | {q.get('fullScanGroupByType')} | {round(d.get('dbFileBytes', 0) / 1048576, 1)} |")
    if missing:
        lines += ["", "## Missing expected suites", "",
                  "No result for these suites: the task did not run, failed before testing, or discovered no test "
                  "(for example JUnit Jupiter tests in an Android module, or tests pinned to SDKs outside -ProbolectricSdks).",
                  ""] + [f"- `{m}`" for m in missing]
    if below:
        lines += ["", "## Suites below their minimum (tools/test-minimums.txt)", ""] + \
                 [f"- `{b['suite']}`: {b['distinct']} distinct tests, minimum {b['minimum']}" for b in below]
    if skipped_not_allowed:
        lines += ["", f"## Skipped tests not on {os.path.basename(a.skipped_allowlist)}", ""] + \
                 [f"- `{sk['class']}#{sk['name']}` ({sk['suite']}, {sk['sdk']})" for sk in skipped_not_allowed]
    if skipped_allowed:
        lines += ["", "## Allowed skipped tests", ""] + \
                 [f"- `{sk['class']}#{sk['name']}` ({sk['suite']}, {sk['sdk']}): {sk['reason']}" for sk in skipped_allowed]
    if allowlist_errors:
        lines += ["", "## Malformed allow-list lines", ""] + [f"- {err}" for err in allowlist_errors]
    if stale:
        lines += ["", f"## Stale suites (results older than {a.fresh_after})", ""] + [f"- `{m}`" for m in sorted(stale)]
    if gate_failures:
        lines += ["", "## Coverage gates not met (enforced)", ""] + \
                 [f"- `{r['module']}` {r['report']}: {r['status']}" + (": " + "; ".join(r["misses"]) if r["misses"] else "")
                  for r in gate_failures]
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
    return 1 if (failures or missing or below or stale or skipped_not_allowed or allowlist_errors or gate_failures) else 0


if __name__ == "__main__":
    sys.exit(main())
