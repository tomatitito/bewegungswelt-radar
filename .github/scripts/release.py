"""Plan/publish the Gradle version from a tested main-branch push. No tag replacement."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.request


REPOSITORY = "tomatitito/bewegungswelt-radar"
APK_NAME = re.compile(r"bewegungswelt-radar-([1-9][0-9]*)\.apk")


def api(path, data=None):
    request = urllib.request.Request(
        f"https://api.github.com/repos/{REPOSITORY}/{path}",
        data=json.dumps(data).encode() if data is not None else None,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": "Bearer " + os.environ["GH_TOKEN"],
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            result = json.load(response)
            if not isinstance(result, dict):
                raise ValueError("Malformed GitHub response")
            return result
    except urllib.error.HTTPError as error:
        if data is None and error.code == 404:
            try:
                missing = json.load(error)
            except (ValueError, OSError):
                raise ValueError("Invalid GitHub 404 response") from None
            if isinstance(missing, dict) and missing.get("message") == "Not Found":
                return None
        raise ValueError(f"GitHub request failed (HTTP {error.code}); release refused") from None
    except (urllib.error.URLError, OSError, ValueError):
        raise ValueError("Cannot read GitHub state; release refused") from None


def release_code(release):
    if (release.get("draft") is not False or release.get("prerelease") is not False
            or not isinstance(release.get("assets"), list)):
        raise ValueError("Expected a complete public stable release")
    assets = release["assets"]
    if any(not isinstance(asset, dict) or not isinstance(asset.get("name"), str) for asset in assets):
        raise ValueError("Malformed release assets")
    apks = [asset for asset in assets if asset["name"].lower().endswith(".apk")]
    match = APK_NAME.fullmatch(apks[0]["name"]) if len(apks) == 1 else None
    if (match is None or not isinstance(apks[0].get("size"), int) or apks[0]["size"] <= 0
            or apks[0].get("state") != "uploaded"):
        raise ValueError("Release must have exactly one uploaded version-code-named APK")
    return int(match[1])


def plan():
    if (os.environ["GITHUB_REPOSITORY"] != REPOSITORY
            or os.environ["GITHUB_REF"] != "refs/heads/main"
            or not re.fullmatch(r"[0-9a-f]{40}", os.environ["GITHUB_SHA"])):
        raise ValueError("Releases require a main-branch commit in the application repository")
    build = Path("app/build.gradle.kts").read_text()
    codes = re.findall(r"^\s*versionCode\s*=\s*([1-9][0-9]*)\s*$", build, re.M)
    names = re.findall(r'^\s*versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"\s*$', build, re.M)
    if len(codes) != 1 or len(names) != 1:
        raise ValueError("Expected one literal positive versionCode and stable versionName")
    code, tag = int(codes[0]), "v" + names[0]
    asset = f"bewegungswelt-radar-{code}.apk"
    latest = api("releases/latest")
    latest_code = release_code(latest) if latest is not None else None
    if latest_code is not None and code < latest_code:
        raise ValueError("versionCode must not go backwards")
    release = api(f"releases/tags/{tag}")
    ref = api(f"git/ref/tags/{tag}")
    commit = None
    if ref is not None:
        commit = api(f"commits/{tag}")
        if commit is None or not re.fullmatch(r"[0-9a-f]{40}", str(commit.get("sha"))):
            raise ValueError("Cannot resolve existing release tag")
        commit = commit["sha"]
    if release is not None:
        published_code = release_code(release)
        checksums = [a for a in release["assets"] if a["name"] == asset + ".sha256"]
        if (published_code != code or release.get("tag_name") != tag or commit is None
                or len(checksums) != 1 or not isinstance(checksums[0].get("size"), int)
                or checksums[0]["size"] <= 0 or checksums[0].get("state") != "uploaded"):
            raise ValueError("Existing release is incomplete or conflicts with the Gradle version")
        return tag, asset, False, commit
    if latest_code is not None and code <= latest_code:
        raise ValueError("A new release requires a strictly higher versionCode")
    if commit is not None and commit != os.environ["GITHUB_SHA"]:
        raise ValueError("Unpublished version tag points to a different commit; never move it")
    return tag, asset, True, commit


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", action="store_true", help="Recheck state and publish the already-tested APK")
    args = parser.parse_args(argv)
    try:
        tag, asset, needed, commit = plan()
        if args.publish and needed:
            directory = Path(os.environ["RUNNER_TEMP"]) / "radar-release-assets"
            files = [directory / asset, directory / (asset + ".sha256")]
            if any(not file.is_file() or file.stat().st_size == 0 for file in files):
                raise ValueError("Verified APK and checksum must exist before creating a tag")
            if commit is None:
                api("git/refs", {"ref": f"refs/tags/{tag}", "sha": os.environ["GITHUB_SHA"]})
            # Verify the remote ref, including a retry's pre-existing tag, before publishing.
            resolved = api(f"commits/{tag}")
            if resolved is None or resolved.get("sha") != os.environ["GITHUB_SHA"]:
                raise ValueError("Release tag must identify the tested commit")
            subprocess.run([
                "gh", "release", "create", tag, "--repo", REPOSITORY,
                "--verify-tag", "--target", os.environ["GITHUB_SHA"],
                "--title", tag, "--generate-notes", "--latest", *(str(file) for file in files),
            ], check=True)
            print(f"Published {tag} from the tested commit")
        elif not needed:
            print(f"{tag} is already published; no signing or release needed")
        else:
            print(f"{tag} is not published; signed build required")
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"needed={str(needed).lower()}\ntag={tag}\nasset={asset}\n")
        return 0
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f"Release refused: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
