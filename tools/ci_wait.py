#!/usr/bin/env python3
"""Wait for the GitHub Actions CI run of a commit and print a short result.

Usage (run it in the background; it exits when the run completes):
    python3 tools/ci_wait.py [--branch BRANCH] [--sha SHA] [--timeout SECONDS]

Defaults: the current branch and HEAD commit, 45 minutes. Exit codes: 0 success, 1 failure or cancelled,
2 timeout, 3 API error. For the failure details call the GitHub MCP tool get_job_logs on the printed job id
and look for the "CI DIGEST" block and the "Test counts" section.

Uses the GitHub REST API through the container's proxy (no token needed for this repository).
"""

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = "https://api.github.com"


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def repo_slug() -> str:
    url = git("remote", "get-url", "origin")
    slug = url.split("github.com")[-1].lstrip(":/")
    return slug[:-4] if slug.endswith(".git") else slug


def get(path: str) -> dict:
    req = urllib.request.Request(API + path, headers={"Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def find_run(slug: str, branch: str, sha: str) -> dict | None:
    runs = get(f"/repos/{slug}/actions/runs?branch={branch}&per_page=20").get("workflow_runs", [])
    matching = [r for r in runs if r.get("head_sha") == sha]
    return max(matching, key=lambda r: r["id"]) if matching else None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--branch", default=None)
    parser.add_argument("--sha", default=None)
    parser.add_argument("--timeout", type=int, default=45 * 60)
    parser.add_argument("--interval", type=int, default=30)
    args = parser.parse_args()

    slug = repo_slug()
    branch = args.branch or git("rev-parse", "--abbrev-ref", "HEAD")
    sha = args.sha or git("rev-parse", "HEAD")
    deadline = time.monotonic() + args.timeout
    run = None
    errors = 0
    while time.monotonic() < deadline:
        try:
            run = find_run(slug, branch, sha)
            errors = 0
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            errors += 1
            if errors >= 10:
                print(f"API error: {e}")
                return 3
        if run and run.get("status") == "completed":
            break
        time.sleep(args.interval)
    else:
        state = run.get("status") if run else "no run found"
        print(f"TIMEOUT after {args.timeout}s waiting for CI on {branch}@{sha[:7]} ({state})")
        return 2

    print(f"RUN {run['id']} {run['conclusion']} {run['html_url']}")
    jobs = get(f"/repos/{slug}/actions/runs/{run['id']}/jobs?per_page=50").get("jobs", [])
    for job in jobs:
        failed_steps = [s["name"] for s in job.get("steps", []) if s.get("conclusion") == "failure"]
        line = f"JOB {job['id']} {job['conclusion']} {job['name']}"
        if failed_steps:
            line += " | failed steps: " + "; ".join(failed_steps)
        print(line)
    return 0 if run["conclusion"] == "success" else 1


if __name__ == "__main__":
    sys.exit(main())
