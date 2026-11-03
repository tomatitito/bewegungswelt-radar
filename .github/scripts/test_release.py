"""Release-command contract tests with simulated GitHub, never real credentials/writes."""

from contextlib import ExitStack, redirect_stderr, redirect_stdout
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

from release import main


SHA = "a" * 40
OLD_SHA = "b" * 40


def published(code=2, tag="v0.2.0"):
    name = f"bewegungswelt-radar-{code}.apk"
    return {"tag_name": tag, "draft": False, "prerelease": False, "assets": [
        {"name": name, "size": 100, "state": "uploaded"},
        {"name": name + ".sha256", "size": 100, "state": "uploaded"},
    ]}


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        root = Path(self.stack.enter_context(tempfile.TemporaryDirectory()))
        previous = Path.cwd()
        os.chdir(root)
        self.stack.callback(os.chdir, previous)
        Path("app").mkdir()
        Path("app/build.gradle.kts").write_text('versionCode = 2\nversionName = "0.2.0"\n')
        self.output = root / "outputs"
        self.stack.enter_context(patch.dict(os.environ, {
            "GITHUB_REPOSITORY": "tomatitito/bewegungswelt-radar", "GITHUB_REF": "refs/heads/main",
            "GITHUB_SHA": SHA, "GH_TOKEN": "fixture-token", "GITHUB_OUTPUT": str(self.output),
            "RUNNER_TEMP": str(root),
        }))
        self.latest = self.release = self.tag_sha = None
        self.errors = {}
        self.publication = None
        self.fail_publication = False
        self.stack.enter_context(patch("urllib.request.urlopen", side_effect=self.github))
        self.stack.enter_context(patch("subprocess.run", side_effect=self.publish))

    def github(self, request, timeout):
        prefix = "https://api.github.com/repos/tomatitito/bewegungswelt-radar/"
        self.assertTrue(request.full_url.startswith(prefix))
        path = request.full_url.removeprefix(prefix)
        if path in self.errors:
            raise self.errors[path]
        if request.get_method() == "POST":
            self.assertEqual(path, "git/refs")
            data = json.loads(request.data)
            self.assertEqual(data, {"ref": "refs/tags/v0.2.0", "sha": SHA})
            self.assertIsNone(self.tag_sha, "Must never replace a tag")
            self.tag_sha = data["sha"]
            result = {"ref": data["ref"], "object": {"sha": self.tag_sha}}
        else:
            result = {
                "releases/latest": self.latest, "releases/tags/v0.2.0": self.release,
                "git/ref/tags/v0.2.0": {"object": {"sha": self.tag_sha}} if self.tag_sha else None,
                "commits/v0.2.0": {"sha": self.tag_sha} if self.tag_sha else None,
            }[path]
        if result is None:
            raise urllib.error.HTTPError(request.full_url, 404, "Not Found", {}, io.BytesIO(b'{"message":"Not Found"}'))
        return io.BytesIO(json.dumps(result).encode())

    def publish(self, command, check):
        self.assertTrue(check)
        self.assertEqual(command[:4], ["gh", "release", "create", "v0.2.0"])
        self.assertIn("--verify-tag", command)
        self.assertIn("--latest", command)
        self.assertNotIn("--clobber", command)
        target = command[command.index("--target") + 1]
        self.assertEqual(target, SHA)
        self.assertEqual(command[command.index("--repo") + 1], "tomatitito/bewegungswelt-radar")
        self.assertEqual(self.tag_sha, target)
        names = [Path(path).name for path in command[-2:]]
        self.assertEqual(names, ["bewegungswelt-radar-2.apk", "bewegungswelt-radar-2.apk.sha256"])
        if self.fail_publication:
            raise subprocess.CalledProcessError(1, command)
        self.publication = {"tag": command[3], "commit": target, "assets": names}
        self.latest = self.release = published()

    def run_command(self, *arguments):
        self.output.unlink(missing_ok=True)
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            result = main(list(arguments))
        outputs = dict(line.split("=", 1) for line in self.output.read_text().splitlines()) if self.output.exists() else {}
        return result, outputs

    def stage(self):
        directory = Path(os.environ["RUNNER_TEMP"]) / "radar-release-assets"
        directory.mkdir(exist_ok=True)
        for name in ["bewegungswelt-radar-2.apk", "bewegungswelt-radar-2.apk.sha256"]:
            (directory / name).write_text("verified fixture")

    def test_first_release_plans_without_writes_then_publishes_the_tested_commit(self):
        self.assertEqual(self.run_command(), (0, {
            "needed": "true", "tag": "v0.2.0", "asset": "bewegungswelt-radar-2.apk",
        }))
        self.assertIsNone(self.tag_sha)
        self.assertIsNone(self.publication)
        self.stage()
        self.assertEqual(self.run_command("--publish")[0], 0)
        self.assertEqual(self.publication["commit"], SHA)
        self.assertEqual(self.run_command("--publish")[1]["needed"], "false")

    def test_ordinary_push_of_published_version_skips_without_signing_files(self):
        self.latest = self.release = published()
        self.tag_sha = OLD_SHA  # newer main commits need not be release commits
        self.assertEqual(self.run_command()[1]["needed"], "false")
        self.assertEqual(self.run_command("--publish")[1]["needed"], "false")
        self.assertEqual(self.tag_sha, OLD_SHA)
        self.assertIsNone(self.publication)

    def test_new_version_requires_higher_code(self):
        for code, expected in [(1, 0), (2, 1), (3, 1)]:
            with self.subTest(latest_code=code):
                self.latest = published(code, "v0.1.0")
                self.assertEqual(self.run_command()[0], expected)
        self.assertIsNone(self.tag_sha)

    def test_reusing_a_published_name_with_another_code_fails(self):
        self.release = published(1)
        self.tag_sha = OLD_SHA
        self.assertEqual(self.run_command()[0], 1)
        self.assertEqual(self.tag_sha, OLD_SHA)

    def test_incomplete_or_invalid_release_is_not_silently_skipped(self):
        complete = published()
        cases = [dict(complete, draft=True), dict(complete, prerelease=True), dict(complete, assets=None),
                 dict(complete, assets=[]), dict(complete, assets=complete["assets"][:1]),
                 dict(complete, assets=complete["assets"] + [complete["assets"][0]]),
                 dict(complete, assets=complete["assets"] + [complete["assets"][1]])]
        for field, value in [("size", 0), ("state", "new"), ("name", "other.apk")]:
            invalid = copy.deepcopy(complete)
            invalid["assets"][0][field] = value
            cases.append(invalid)
        self.tag_sha = OLD_SHA
        for release in cases:
            with self.subTest(release=release):
                self.release = release
                self.assertEqual(self.run_command()[0], 1)

    def test_tag_without_release_can_only_be_retried_at_the_same_commit(self):
        self.tag_sha = OLD_SHA
        self.assertEqual(self.run_command()[0], 1)
        self.tag_sha = SHA
        self.stage()
        self.assertEqual(self.run_command("--publish")[0], 0)
        self.assertEqual(self.publication["commit"], SHA)

    def test_publication_failure_keeps_tag_and_allows_original_commit_retry(self):
        self.stage()
        self.fail_publication = True
        self.assertEqual(self.run_command("--publish")[0], 1)
        self.assertEqual(self.tag_sha, SHA)
        self.assertIsNone(self.release)
        self.fail_publication = False
        self.assertEqual(self.run_command("--publish")[0], 0)

    def test_no_tag_is_created_without_verified_build_outputs(self):
        self.assertEqual(self.run_command("--publish")[0], 1)
        self.assertIsNone(self.tag_sha)
        self.assertIsNone(self.publication)

    def test_state_is_rechecked_after_the_build(self):
        self.assertEqual(self.run_command()[1]["needed"], "true")
        self.latest = published(3, "v0.3.0")
        self.stage()
        self.assertEqual(self.run_command("--publish")[0], 1)
        self.assertIsNone(self.tag_sha)

    def test_api_failures_never_mean_no_previous_release(self):
        for path in ["releases/latest", "releases/tags/v0.2.0", "git/ref/tags/v0.2.0"]:
            for status in [403, 429, 500]:
                with self.subTest(path=path, status=status):
                    self.errors = {path: urllib.error.HTTPError(path, status, "failure", {}, io.BytesIO(b"{}"))}
                    self.assertEqual(self.run_command()[0], 1)
        for error in [urllib.error.URLError("offline"),
                      urllib.error.HTTPError("latest", 404, "missing", {}, io.BytesIO(b"not JSON")),
                      urllib.error.HTTPError("latest", 404, "missing", {}, io.BytesIO(b'{"message":"unexpected"}'))]:
            self.errors = {"releases/latest": error}
            self.assertEqual(self.run_command()[0], 1)
        self.assertIsNone(self.tag_sha)

    def test_only_valid_main_repository_versions_can_release(self):
        for key, value in [("GITHUB_REF", "refs/heads/feature"), ("GITHUB_REF", "refs/tags/v0.2.0"),
                           ("GITHUB_REPOSITORY", "someone/fork"), ("GITHUB_SHA", "main")]:
            with self.subTest(key=key, value=value), patch.dict(os.environ, {key: value}):
                self.assertEqual(self.run_command()[0], 1)
        for build in ['versionCode = 0\nversionName = "0.2.0"',
                      'versionCode = 2\nversionName = "0.2.0-rc1"',
                      'versionCode = 2\nversionCode = 3\nversionName = "0.2.0"']:
            Path("app/build.gradle.kts").write_text(build)
            self.assertEqual(self.run_command()[0], 1)
        self.assertIsNone(self.tag_sha)


if __name__ == "__main__":
    unittest.main()
