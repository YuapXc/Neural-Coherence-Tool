import os
import subprocess
import tempfile
import unittest

from release_notes import generate


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.cwd = os.getcwd()
        os.chdir(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.invalid")

    def tearDown(self):
        os.chdir(self.cwd)
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.check_output(["git", *args], text=True).strip()

    def commit(self, message):
        self.git("commit", "--allow-empty", "-qm", message)
        return self.git("rev-parse", "HEAD")

    def test_previous_tag_and_exact_range(self):
        self.commit("old change")
        self.git("tag", "v1.2.1-fix")
        self.commit("fix: 修复面板 [layout]")
        head = self.commit("build: update Gradle")
        self.git("tag", "-a", "v1.3.0", "-m", "release")
        notes = generate("v1.3.0", "owner/repo", head)
        self.assertNotIn("old change", notes)
        self.assertIn(r"修复面板 \[layout\]", notes)
        self.assertIn("build: update Gradle", notes)
        self.assertIn("compare/v1.2.1-fix...v1.3.0", notes)
        self.assertEqual(notes, generate("v1.3.0", "owner/repo", head))

    def test_first_release(self):
        head = self.commit("initial commit")
        self.git("tag", "v1.0.0")
        notes = generate("v1.0.0", "owner/repo", head)
        self.assertIn("initial commit", notes)
        self.assertIn("/commits/v1.0.0", notes)

    def test_wrong_commit_and_invalid_tag_are_rejected(self):
        old = self.commit("old")
        self.git("tag", "v1.0.0")
        head = self.commit("new")
        with self.assertRaises(ValueError):
            generate("v1.0.0", "owner/repo", head)
        with self.assertRaises(ValueError):
            generate("--help", "owner/repo", old)


if __name__ == "__main__":
    unittest.main()
