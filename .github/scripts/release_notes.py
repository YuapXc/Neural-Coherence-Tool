"""Generate release notes from commits since the nearest previous version tag."""

import argparse
import re
import subprocess
from pathlib import Path
from urllib.parse import quote


def git(*args):
    return subprocess.check_output(["git", *args], text=True, encoding="utf-8").strip()


def generate(tag, repository, expected_commit=None):
    if not re.fullmatch(r"v?\d+\.\d+\.\d+(?:[-.][A-Za-z0-9.-]+)?", tag):
        raise ValueError("Release tag must be a version such as v1.3.4")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Repository must be owner/name")
    commit = git("rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}")
    if expected_commit and commit != git("rev-parse", "--verify", f"{expected_commit}^{{commit}}"):
        raise ValueError("Release tag does not point to the built commit")
    # Search from the parent so another tag on this commit cannot hide changes.
    parent = subprocess.run(["git", "rev-parse", "--verify", f"{commit}^"],
                            capture_output=True, text=True)
    previous = None
    if parent.returncode == 0:
        result = subprocess.run(
            ["git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*",
             "--match", "[0-9]*", parent.stdout.strip()],
            capture_output=True, text=True, encoding="utf-8")
        if result.returncode == 0:
            previous = result.stdout.strip()
    revision = f"{previous}..{commit}" if previous else commit
    lines = ["## 本次更新", ""]
    records = git("log", "--reverse", "--no-merges", "--format=%H%x09%s", revision)
    base = f"https://github.com/{repository}"
    for record in records.splitlines():
        sha, subject = record.split("\t", 1)
        subject = re.sub(r"([\\`*_{}\[\]<>])", r"\\\1", subject)
        lines.append(f"- {subject} ([{sha[:7]}]({base}/commit/{sha}))")
    if not records:
        lines.append("- 本次没有新增的非合并提交。")
    url = (f"{base}/compare/{quote(previous, safe='')}...{quote(tag, safe='')}"
           if previous else f"{base}/commits/{quote(tag, safe='')}")
    lines.extend(["", f"[完整变更]({url})", ""])
    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--expected-commit")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    notes = generate(args.tag, args.repository, args.expected_commit)
    args.output.write_text(notes, encoding="utf-8")
