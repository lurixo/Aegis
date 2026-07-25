#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import argparse
import hashlib
import json
import re
import shutil
import sys
import urllib.request
import zipfile
from datetime import datetime
from pathlib import Path

RELEASES_URL = "https://api.github.com/repos/lurixo/Aegis/releases?per_page=100"
RELEASE_TAG_PATTERN = re.compile(r"dict-(v\d+\.\d+\.\d+)-r([1-9]\d*)")
RELEASE_URL_PREFIX = "https://github.com/lurixo/Aegis/releases/tag"
DOWNLOAD_URL_PREFIX = "https://github.com/lurixo/Aegis/releases/download"
UPDATE_ASSET_NAME = "aegis-dictionary-update.json"
BUILD_INFO_ASSET_NAME = "aegis-build-info.json"
SOURCE_REPO = "https://github.com/amzxyz/rime-wanxiang"
RUNTIME_BINS = ("aegis_dict.bin", "aegis_t9.bin", "aegis_jianpin.bin")
RELEASES_PAGE_SIZE = 100


def normalize_sha256(value):
    raw = (value or "").strip().lower()
    if raw.startswith("sha256:"):
        raw = raw[len("sha256:"):]
    return raw if re.fullmatch(r"[0-9a-f]{64}", raw) else None


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def http_get(url, timeout):
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "Aegis-test-dict-fetch",
        },
    )
    return urllib.request.urlopen(request, timeout=timeout)


def parse_published_at(value):
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ValueError("dictionary release has no valid published_at")
    parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    if parsed.tzinfo is None:
        raise ValueError("dictionary release published_at has no timezone")
    return parsed


def expected_asset_url(tag, name):
    return f"{DOWNLOAD_URL_PREFIX}/{tag}/{name}"


def select_dictionary_release(releases):
    if not isinstance(releases, list):
        raise ValueError("GitHub Releases response is not a list")
    candidates = []
    for release in releases:
        if not isinstance(release, dict):
            raise ValueError("GitHub Releases response contains a non-object")
        tag = release.get("tag_name")
        match = RELEASE_TAG_PATTERN.fullmatch(tag or "")
        if match is None:
            continue
        if not isinstance(release.get("draft"), bool) or not isinstance(release.get("prerelease"), bool):
            raise ValueError(f"{tag} has invalid release flags")
        if release["draft"] or release["prerelease"]:
            continue
        published_at = release.get("published_at")
        published = parse_published_at(published_at)
        release_url = release.get("html_url")
        if release_url != f"{RELEASE_URL_PREFIX}/{tag}":
            raise ValueError(f"{tag} has an unexpected release URL")

        pack_name = f"aegis_dict_pack_{tag}.zip"
        expected_names = {pack_name, UPDATE_ASSET_NAME, BUILD_INFO_ASSET_NAME}
        assets = release.get("assets")
        if not isinstance(assets, list) or len(assets) != len(expected_names):
            raise ValueError(f"{tag} does not have the exact dictionary asset set")
        by_name = {}
        for asset in assets:
            if not isinstance(asset, dict):
                raise ValueError(f"{tag} has a non-object asset")
            name = asset.get("name")
            if name not in expected_names or name in by_name:
                raise ValueError(f"{tag} has an unexpected or duplicate asset")
            size = asset.get("size")
            digest = normalize_sha256(asset.get("digest"))
            if not isinstance(size, int) or size <= 0 or digest is None:
                raise ValueError(f"{tag} has invalid asset metadata")
            if asset.get("browser_download_url") != expected_asset_url(tag, name):
                raise ValueError(f"{tag} has an unexpected asset URL")
            by_name[name] = asset
        if set(by_name) != expected_names:
            raise ValueError(f"{tag} is missing a dictionary asset")

        pack = by_name[pack_name]
        candidates.append(
            (
                published,
                {
                    "tag": tag,
                    "source_tag": match.group(1),
                    "release_url": release_url,
                    "published_at": published_at,
                    "manifest_url": by_name[UPDATE_ASSET_NAME]["browser_download_url"],
                    "pack_name": pack_name,
                    "pack_url": pack["browser_download_url"],
                    "pack_size": pack["size"],
                    "pack_sha256": normalize_sha256(pack["digest"]),
                },
            )
        )
    if not candidates:
        raise ValueError("no eligible versioned dictionary release")
    latest = max(item[0] for item in candidates)
    selected = [item[1] for item in candidates if item[0] == latest]
    if len(selected) != 1:
        raise ValueError("latest versioned dictionary release is tied")
    return selected[0]


def dictionary_asset_from_manifest(manifest, release):
    if not isinstance(manifest, dict):
        raise ValueError("dictionary manifest is not an object")
    if manifest.get("schema_version") != 1 or manifest.get("kind") != "dictionary_update":
        raise ValueError("unexpected dictionary manifest")
    asset = manifest.get("asset")
    source = manifest.get("source")
    if not isinstance(asset, dict) or not isinstance(source, dict):
        raise ValueError("dictionary manifest is missing metadata")
    name = asset.get("name")
    url = asset.get("url")
    sha256 = normalize_sha256(asset.get("sha256"))
    published_at = asset.get("published_at")
    if (
        name != release["pack_name"]
        or url != release["pack_url"]
        or sha256 != release["pack_sha256"]
        or asset.get("size_bytes") != release["pack_size"]
        or asset.get("release_tag") != release["tag"]
        or asset.get("release_url") != release["release_url"]
        or asset.get("prerelease") is not False
        or published_at not in (None, release["published_at"])
        or source.get("repo") != SOURCE_REPO
        or source.get("ref_type") != "tag"
        or source.get("tag") != release["source_tag"]
        or source.get("branch") is not None
        or re.fullmatch(r"[0-9a-f]{40}", source.get("commit") or "") is None
    ):
        raise ValueError("dictionary manifest does not match its versioned release")
    return url, sha256, name


def fetch_release_pages(releases_url):
    releases = []
    page = 1
    separator = "&" if "?" in releases_url else "?"
    while True:
        page_url = releases_url if page == 1 else f"{releases_url}{separator}page={page}"
        with http_get(page_url, 60) as response:
            page_releases = json.loads(response.read().decode("utf-8"))
        if not isinstance(page_releases, list):
            raise ValueError("GitHub Releases response is not a list")
        releases.extend(page_releases)
        if len(page_releases) < RELEASES_PAGE_SIZE:
            return releases
        page += 1


def resolve_asset(releases_url):
    releases = fetch_release_pages(releases_url)
    release = select_dictionary_release(releases)
    with http_get(release["manifest_url"], 60) as response:
        manifest = json.loads(response.read().decode("utf-8"))
    return dictionary_asset_from_manifest(manifest, release)


def download_to(url, dest, timeout):
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    with http_get(url, timeout) as response, part.open("wb") as out:
        shutil.copyfileobj(response, out, 1024 * 1024)
    part.replace(dest)


def ensure_pack(url, expected_sha256, zip_path, local_zip, timeout):
    if local_zip is not None:
        source = Path(local_zip)
        if not source.exists():
            raise SystemExit(f"--zip not found: {source}")
        actual = sha256_file(source)
        if actual != expected_sha256:
            raise SystemExit(f"--zip sha256 mismatch: {actual} != {expected_sha256}")
        return source
    if zip_path.exists() and sha256_file(zip_path) == expected_sha256:
        return zip_path
    download_to(url, zip_path, timeout)
    actual = sha256_file(zip_path)
    if actual != expected_sha256:
        zip_path.unlink(missing_ok=True)
        raise SystemExit(f"downloaded pack sha256 mismatch: {actual} != {expected_sha256}")
    return zip_path


def target_for(entry_name):
    name = entry_name.replace("\\", "/").rsplit("/", 1)[-1].lower()
    if "jianpin" in name:
        return "aegis_jianpin.bin"
    if "t9" in name:
        return "aegis_t9.bin"
    if "dict" in name:
        return "aegis_dict.bin"
    return None


def extract_pack(zip_path, assets_dir):
    assets_dir.mkdir(parents=True, exist_ok=True)
    produced = {}
    with zipfile.ZipFile(zip_path) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            target = target_for(entry.filename)
            if target is None:
                continue
            destination = assets_dir / target
            part = destination.with_name(destination.name + ".part")
            with archive.open(entry) as source, part.open("wb") as out:
                shutil.copyfileobj(source, out, 1024 * 1024)
            part.replace(destination)
            produced[target] = destination.stat().st_size
    missing = [name for name in RUNTIME_BINS if name not in produced]
    if missing:
        raise SystemExit("pack is missing expected tables: " + ", ".join(missing))
    small = [name for name in RUNTIME_BINS if produced[name] <= 1024]
    if small:
        raise SystemExit("pack tables are implausibly small: " + ", ".join(small))
    return produced


def main(argv):
    repo_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(
        description=(
            "Discover the latest versioned dictionary release and unpack its tables into the app assets "
            "so the decode-quality unit tests run against the full dictionary."
        )
    )
    parser.add_argument("--releases-url", default=RELEASES_URL, help="GitHub Releases API URL.")
    parser.add_argument(
        "--assets-dir",
        default=str(repo_root / "app" / "src" / "main" / "assets"),
        help="Directory that receives aegis_dict.bin / aegis_t9.bin / aegis_jianpin.bin.",
    )
    parser.add_argument(
        "--cache-dir",
        default=str(repo_root / "build" / "test-dict-cache"),
        help="Directory that keeps the downloaded pack between runs.",
    )
    parser.add_argument("--zip", dest="local_zip", help="Use a local pack zip instead of downloading.")
    parser.add_argument("--timeout", type=int, default=300, help="Download timeout in seconds.")
    parser.add_argument(
        "--print-sha",
        action="store_true",
        help="Print the manifest pack sha256 and exit (for the CI cache key).",
    )
    args = parser.parse_args(argv)

    try:
        url, expected_sha256, asset_name = resolve_asset(args.releases_url)
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise SystemExit(str(error)) from error
    if args.print_sha:
        print(expected_sha256)
        return 0

    zip_path = ensure_pack(
        url, expected_sha256, Path(args.cache_dir) / asset_name, args.local_zip, args.timeout
    )
    assets_dir = Path(args.assets_dir)
    produced = extract_pack(zip_path, assets_dir)
    print(f"dictionary pack {expected_sha256} unpacked into {assets_dir}")
    for name in RUNTIME_BINS:
        print(f"  {name}  {produced[name]} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
