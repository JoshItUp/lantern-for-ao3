"""Tests for changelog.py. Each test builds its own throwaway git repo."""

import os
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "changelog.py")

GIT_ENV = {
    "GIT_AUTHOR_NAME": "Test",
    "GIT_AUTHOR_EMAIL": "test@example.invalid",
    "GIT_COMMITTER_NAME": "Test",
    "GIT_COMMITTER_EMAIL": "test@example.invalid",
    "GIT_CONFIG_NOSYSTEM": "1",
}

HEADER = "# Changelog\n\nAll notable changes to this project are documented here.\n\n"


class ChangelogTest(unittest.TestCase):
    def setUp(self):
        self.repo = tempfile.mkdtemp(prefix="changelog-test-")
        self.env = dict(os.environ, **GIT_ENV)
        self.env["HOME"] = self.repo  # keep the host's global git config out
        self.git("init", "-q", "-b", "main")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "tag.gpgsign", "false")
        self.commit("chore: initial commit")

    def tearDown(self):
        shutil.rmtree(self.repo, ignore_errors=True)

    # -- helpers ---------------------------------------------------------

    def git(self, *args):
        return subprocess.run(
            ["git", *args], cwd=self.repo, env=self.env, check=True,
            capture_output=True, text=True,
        ).stdout

    def commit(self, message):
        self.git("commit", "-q", "--allow-empty", "-m", message)

    def tag(self, name):
        self.git("tag", name)

    def merge_pr(self, number, branch, title, commits):
        self.git("checkout", "-q", "-b", branch)
        for c in commits:
            self.commit(c)
        self.git("checkout", "-q", "main")
        self.git(
            "merge", "-q", "--no-ff", branch,
            "-m", f"Merge pull request #{number} from owner/{branch}",
            "-m", title,
        )

    def run_script(self, *args):
        return subprocess.run(
            [sys.executable, SCRIPT, *args], cwd=self.repo, env=self.env,
            capture_output=True, text=True, encoding="utf-8",
        )

    def write_changelog(self, text):
        with open(os.path.join(self.repo, "CHANGELOG.md"), "w", encoding="utf-8", newline="\n") as f:
            f.write(text)

    def read_changelog(self):
        with open(os.path.join(self.repo, "CHANGELOG.md"), encoding="utf-8") as f:
            return f.read()

    def section(self, version="1.1.0", date="2026-01-02"):
        result = self.run_script("section", "--version", version, "--date", date)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    # -- next-version ----------------------------------------------------

    def test_next_version_no_tags_is_1_0_0(self):
        for bump in ("patch", "minor", "major"):
            result = self.run_script("next-version", "--bump", bump)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), "1.0.0")

    def test_next_version_bumps(self):
        self.tag("v1.2.3")
        self.commit("feat: something")
        expected = {"patch": "1.2.4", "minor": "1.3.0", "major": "2.0.0"}
        for bump, version in expected.items():
            result = self.run_script("next-version", "--bump", bump)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), version)

    def test_malformed_latest_tag_fails(self):
        self.tag("v1.0.0")
        self.commit("feat: something")
        self.tag("v1.1")
        result = self.run_script("next-version", "--bump", "patch")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), "")
        self.assertIn("v1.1", result.stderr)

    # -- section ---------------------------------------------------------

    def test_unreleased_section_used_and_reset(self):
        self.write_changelog(
            HEADER
            + "## [Unreleased]\n\n### Added\n- Hand-written summary ✓\n\n"
            + "## [0.9.0] - 2025-12-01\n\n### Fixed\n- Old fix\n"
        )
        self.commit("feat: generated entry that must not appear")
        out = self.section("1.0.0", "2026-01-02")
        self.assertEqual(out.strip(), "### Added\n- Hand-written summary ✓")
        self.assertNotIn("generated entry", out)
        text = self.read_changelog()
        self.assertEqual(
            text,
            HEADER
            + "## [Unreleased]\n\n"
            + "## [1.0.0] - 2026-01-02\n\n### Added\n- Hand-written summary ✓\n\n"
            + "## [0.9.0] - 2025-12-01\n\n### Fixed\n- Old fix\n",
        )

    def test_generated_groups_and_strips_prefixes(self):
        self.write_changelog(HEADER + "## [Unreleased]\n\n")
        self.tag("v1.0.0")
        self.commit("feat(reader): swipe between chapters")
        self.commit("fix: crash on empty summary")
        self.commit("refactor(net): tidy the fetcher")
        self.commit("perf: faster parsing")
        self.commit("build: bump a dependency")
        self.commit("docs: update the README")
        self.commit("test: more tests")
        self.commit("chore: housekeeping")
        self.commit("ci: tweak the workflow")
        self.commit("style: formatting")
        out = self.section("1.1.0", "2026-01-02")
        self.assertEqual(
            out.strip(),
            "### Added\n- Swipe between chapters\n\n"
            "### Changed\n- Tidy the fetcher\n- Faster parsing\n- Bump a dependency\n\n"
            "### Fixed\n- Crash on empty summary",
        )
        text = self.read_changelog()
        self.assertTrue(text.startswith(HEADER + "## [Unreleased]\n\n## [1.1.0] - 2026-01-02\n\n### Added\n"))
        self.assertNotIn("v1.0.0", out)

    def test_merge_commit_uses_pr_title_once(self):
        self.tag("v1.0.0")
        self.merge_pr(8, "feature/kudos", "feat: kudos from the reader",
                      ["feat: kudos button", "fix: kudos button colour"])
        self.commit("fix: direct fix on main")
        out = self.section("1.1.0", "2026-01-02")
        self.assertEqual(out.count("Kudos from the reader"), 1)
        self.assertNotIn("Kudos button", out)
        self.assertNotIn("Merge pull request", out)
        self.assertEqual(
            out.strip(),
            "### Added\n- Kudos from the reader\n\n### Fixed\n- Direct fix on main",
        )

    def test_release_commits_skipped(self):
        self.tag("v1.0.0")
        self.commit("feat: real change")
        self.commit("chore(release): v1.0.1")
        self.commit('Revert "chore(release): v1.0.1"')
        out = self.section("1.0.1", "2026-01-02")
        self.assertEqual(out.strip(), "### Added\n- Real change")

    def test_only_docs_goes_under_changed(self):
        self.tag("v1.0.0")
        self.commit("docs: clarify the install steps")
        self.commit("test: cover the parser")
        self.commit("chore(release): v1.0.0")
        out = self.section("1.0.1", "2026-01-02")
        self.assertEqual(
            out.strip(),
            "### Changed\n- Clarify the install steps\n- Cover the parser",
        )


if __name__ == "__main__":
    unittest.main()
