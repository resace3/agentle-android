#!/usr/bin/env python3
"""Print a compact digest of a failed Gradle build so CI logs can be triaged from their last lines.

Usage: tools/ci_digest.py [GRADLE_LOG] (default build/gradle-output.log)
Prints Kotlin compiler errors, the "What went wrong" summary of every failed task, and lint errors from the text
reports (lint.textReport = true). Output is capped so the digest stays readable.
"""
import glob, os, re, sys

log_path = sys.argv[1] if len(sys.argv) > 1 else "build/gradle-output.log"
lines = open(log_path, errors="replace").read().splitlines() if os.path.exists(log_path) else []

print("=" * 30, "CI DIGEST", "=" * 30)
compile_errors = [l for l in lines if re.match(r"^e: ", l)]
print(f"Kotlin compiler errors: {len(compile_errors)}")
for l in compile_errors[:40]:
    print("  " + l.replace(os.getcwd() + "/", "")[:300])

failures = []
for i, l in enumerate(lines):
    if l.startswith("* What went wrong:"):
        block = [x for x in lines[i + 1:i + 6] if x.strip() and not x.startswith("* Try")]
        failures.append(" | ".join(x.strip() for x in block[:3])[:400])
print(f"Failed tasks: {len(failures)}")
for f in dict.fromkeys(failures):
    print("  - " + f)

lint_reports = sorted(glob.glob("**/build/reports/lint-results-*.txt", recursive=True))
lint_errors = []
for path in lint_reports:
    text = open(path, errors="replace").read().splitlines()
    for i, l in enumerate(text):
        if ": Error:" in l:
            lint_errors.append(f"{path.split('/build/')[0]}: {l.strip()[:250]}")
print(f"Lint errors: {len(lint_errors)} (from {len(lint_reports)} text reports)")
for l in lint_errors[:60]:
    print("  " + l)
print("=" * 71)
