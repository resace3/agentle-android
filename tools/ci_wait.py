#!/usr/bin/env python3
"""Wait for the GitHub Actions CI run of a commit and print a short result.

Usage (run it in the background; it exits when the run completes):
    python3 tools/ci_wait.py [--branch BRANCH] [--sha SHA] [--timeout SECONDS]

Defaults: the current branch and HEAD commit, 45 minutes. --sha may be short: a sha the local repository knows is
expanded to its full 40 characters (git rev-parse); any other sha must be at least 7 hex characters and matches a
run whose head sha starts with it. The first line names the sha it waits for. Exit codes: 0 success, 1 failure or
cancelled, 2 timeout, 3 API error, 4 unusable --sha. For the failure details call the GitHub MCP tool get_job_logs
on the printed job id and look for the "CI DIGEST" block and the "Test counts" section.

Uses the GitHub REST API through the container's proxy (no token needed for this repository).
"""

import argparse
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = "https://api.github.com"
SHA_PREFIX = re.compile(r"[0-9a-f]{7,40}")


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


def resolve_sha(sha: str) -> tuple[str, bool] | None:
    """(sha, exact): the full sha when the local repository resolves it, else a hex prefix of 7 to 40 characters."""
    try:
        return git("rev-parse", "--verify", "--quiet", f"{sha}^{{commit}}"), True
    except subprocess.CalledProcessError:
        pass
    prefix = sha.strip().lower()
    if SHA_PREFIX.fullmatch(prefix):
        return prefix, len(prefix) == 40
    return None


def sha_matches(head_sha: str, sha: str, exact: bool) -> bool:
    return head_sha == sha if exact else head_sha.startswith(sha)


def find_run(slug: str, branch: str, sha: str, exact: bool) -> dict | None:
    runs = get(f"/repos/{slug}/actions/runs?branch={branch}&per_page=20").get("workflow_runs", [])
    matching = [r for r in runs if sha_matches(r.get("head_sha") or "", sha, exact)]
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
    resolved = resolve_sha(args.sha or "HEAD")
    if resolved is None:
        print("UNUSABLE --sha: give a commit the local repository knows, or at least 7 hex characters of one")
        return 4
    sha, exact = resolved
    if exact:
        print(f"WAITING for CI on {branch}@{sha}", flush=True)
    else:
        print(f"WAITING for CI on {branch}@{sha}... (prefix; the local repository does not know it)", flush=True)
    deadline = time.monotonic() + args.timeout
    run = None
    errors = 0
    while time.monotonic() < deadline:
        try:
            run = find_run(slug, branch, sha, exact)
            errors = 0
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            errors += 1
            if errors >= 10:
                print(f"API error: {type(e).__name__} {getattr(e, 'code', '')}".rstrip())
                return 3
        if run and run.get("status") == "completed":
            break
        time.sleep(args.interval)
    else:
        state = run.get("status") if run else "no run found"
        print(f"TIMEOUT after {args.timeout}s waiting for CI on {branch}@{sha[:7]} ({state})")
        return 2

    if not exact:
        print(f"MATCHED {run['head_sha']}")
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
