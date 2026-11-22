#!/usr/bin/env python3
"""Release helper: works out the next version and writes CHANGELOG.md sections.

Run from the repository root.

  changelog.py next-version --bump {patch,minor,major}
  changelog.py section --version X.Y.Z --date YYYY-MM-DD

Tags are vMAJOR.MINOR.PATCH, with MINOR and PATCH below 100 (the app's
versionCode is MAJOR*10000 + MINOR*100 + PATCH). With no v* tag the next
version is 1.0.0 whatever the bump.
"""

import argparse
import re
import subprocess
import sys

CHANGELOG = "CHANGELOG.md"
HEADER = (
    "# Changelog\n\n"
    "All notable changes to Lantern are documented in this file.\n\n"
    "The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),\n"
    "and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).\n\n"
)
UNRELEASED = "## [Unreleased]"
GROUPS = ("Added", "Changed", "Fixed")

VERSION_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
CONVENTIONAL_RE = re.compile(r"^(?P<type>[A-Za-z]+)(?:\((?P<scope>[^)]*)\))?!?:\s*(?P<text>.+)$")
PR_MERGE_RE = re.compile(r"^Merge pull request #\d+ from \S+?/(?P<branch>\S+)")

TYPE_GROUP = {
    "feat": "Added",
    "fix": "Fixed",
    "refactor": "Changed",
    "perf": "Changed",
    "build": "Changed",
}
MINOR_TYPES = {"docs", "test", "chore", "ci", "style"}


class ReleaseError(Exception):
    pass


def git(*args):
    result = subprocess.run(["git", *args], capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        raise ReleaseError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def latest_tag():
    """The nearest v* tag reachable from HEAD (what Gradle reads), or None."""
    result = subprocess.run(
        ["git", "describe", "--tags", "--abbrev=0", "--match", "v*"],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        git("rev-parse", "HEAD")  # raises outside a repository or with no commits
        # No v* tag at all is normal; tags that exist but aren't reachable are not.
        if git("tag", "--list", "v*").strip() == "":
            return None
        raise ReleaseError(f"git describe failed: {result.stderr.strip()}")
    return result.stdout.strip()


def parse_version(text, what):
    m = VERSION_RE.match(text)
    if not m:
        raise ReleaseError(f"{what} '{text}' is not MAJOR.MINOR.PATCH")
    major, minor, patch = (int(x) for x in m.groups())
    if minor >= 100 or patch >= 100:
        raise ReleaseError(f"{what} '{text}' has a minor or patch of 100 or more")
    return major, minor, patch


def next_version(bump):
    tag = latest_tag()
    if tag is None:
        return "1.0.0"
    if not tag.startswith("v"):
        raise ReleaseError(f"latest tag '{tag}' is not vMAJOR.MINOR.PATCH")
    major, minor, patch = parse_version(tag[1:], f"latest tag {tag}: version")
    if bump == "major":
        major, minor, patch = major + 1, 0, 0
    elif bump == "minor":
        major, minor, patch = major, minor + 1, 0
    else:
        patch += 1
    version = f"{major}.{minor}.{patch}"
    parse_version(version, "next version")  # minor/patch must stay below 100
    return version


# -- generated sections ------------------------------------------------------


def capitalise(text):
    text = text.strip()
    return text[:1].upper() + text[1:]


def classify(subject):
    """Returns (group, text, minor) for a change line, or None to skip it."""
    subject = subject.strip()
    if not subject:
        return None
    if subject.startswith('Revert "chore(release)'):
        return None
    m = CONVENTIONAL_RE.match(subject)
    if m and m.group("type").lower() in TYPE_GROUP.keys() | MINOR_TYPES:
        kind = m.group("type").lower()
        if kind == "chore" and (m.group("scope") or "").strip().lower() == "release":
            return None
        text = capitalise(m.group("text"))
        if kind in MINOR_TYPES:
            return "Changed", text, True
        return TYPE_GROUP[kind], text, False
    if subject.startswith("Merge "):
        return None  # merge commits other than PR merges carry no change of their own
    return "Changed", capitalise(subject), False


def change_lines(tag):
    """Subjects since `tag` on the first-parent line, oldest first; PR merges use the PR title."""
    rev_range = f"{tag}..HEAD" if tag else "HEAD"
    raw = git("log", "--first-parent", "--reverse", "--format=%s%x1f%b%x1e", rev_range)
    lines = []
    for record in raw.split("\x1e"):
        record = record.strip("\n")
        if not record:
            continue
        subject, _, body = record.partition("\x1f")
        pr = PR_MERGE_RE.match(subject)
        if pr:
            title = next((l.strip() for l in body.splitlines() if l.strip()), "")
            lines.append(title or pr.group("branch"))
        else:
            lines.append(subject)
    return lines


def generate(tag):
    major = {g: [] for g in GROUPS}
    minor = {g: [] for g in GROUPS}
    for line in change_lines(tag):
        entry = classify(line)
        if entry is None:
            continue
        group, text, is_minor = entry
        bucket = (minor if is_minor else major)[group]
        if text not in bucket:
            bucket.append(text)
    chosen = major if any(major.values()) else minor
    parts = []
    for group in GROUPS:
        if chosen[group]:
            parts.append(f"### {group}\n" + "\n".join(f"- {t}" for t in chosen[group]))
    if not parts:
        since = tag or "the first commit"
        raise ReleaseError(f"no changes to release since {since}")
    return "\n\n".join(parts)


# -- CHANGELOG.md editing ----------------------------------------------------


def read_changelog():
    try:
        with open(CHANGELOG, encoding="utf-8") as f:
            return f.read().replace("\r\n", "\n")
    except FileNotFoundError:
        return HEADER + UNRELEASED + "\n\n"


def write_changelog(text):
    with open(CHANGELOG, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def split_unreleased(text):
    """Returns (before, unreleased_body, after) or None when there is no Unreleased heading."""
    m = re.search(r"^## \[Unreleased\][ \t]*\n?", text, re.MULTILINE)
    if not m:
        return None
    rest = text[m.end():]
    nxt = re.search(r"^## ", rest, re.MULTILINE)
    body = rest[:nxt.start()] if nxt else rest
    after = rest[nxt.start():] if nxt else ""
    return text[:m.start()], body, after


def section(version, date):
    parse_version(version, "version")
    if not DATE_RE.match(date):
        raise ReleaseError(f"date '{date}' is not YYYY-MM-DD")
    text = read_changelog()
    if re.search(rf"^## \[{re.escape(version)}\]", text, re.MULTILINE):
        raise ReleaseError(f"{CHANGELOG} already has a section for {version}")
    heading = f"## [{version}] - {date}"
    parts = split_unreleased(text)
    if parts is not None and parts[1].strip():
        before, body, after = parts
        body = body.strip()
    else:
        body = generate(latest_tag())
        if parts is not None:
            before, _, after = parts
        else:
            # No Unreleased heading: put the new one before the first version section.
            m = re.search(r"^## ", text, re.MULTILINE)
            before, after = (text[:m.start()], text[m.start():]) if m else (text, "")
            if before and not before.endswith("\n\n"):
                before = before.rstrip("\n") + "\n\n"
    new_text = f"{before}{UNRELEASED}\n\n{heading}\n\n{body}\n"
    if after:
        new_text += "\n" + after
    write_changelog(new_text)
    return body


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    nv = sub.add_parser("next-version", help="print the next version")
    nv.add_argument("--bump", choices=("patch", "minor", "major"), default="patch")
    sec = sub.add_parser("section", help="write the release section into CHANGELOG.md and print it")
    sec.add_argument("--version", required=True)
    sec.add_argument("--date", required=True)
    args = parser.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")  # the notes can hold non-ASCII on any host
    try:
        if args.command == "next-version":
            print(next_version(args.bump))
        else:
            sys.stdout.write(section(args.version, args.date) + "\n")
    except ReleaseError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
