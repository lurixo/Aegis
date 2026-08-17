#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path
from urllib.parse import urlsplit

DICT_LATEST_TAG = "dict-latest"
MANIFEST_URL = (
    f"https://github.com/lurixo/Aegis/releases/download/{DICT_LATEST_TAG}/aegis-dictionary-update.json"
)
LM_NAME = "aegis_lm.bin"
EN_PACK_NAME = "aegis_en_full.bin"
EN_NAME = "aegis_english.bin"
RUNTIME_BINS = ("aegis_dict.bin", "aegis_t9.bin", "aegis_jianpin.bin", LM_NAME)
TEST_BINS = RUNTIME_BINS + (EN_NAME,)
GRAMMAR_TAG = "LTS"
GRAMMAR_NAME = "wanxiang-lts-zh-hans.gram"
GRAMMAR_RELEASE_API = (
    f"https://api.github.com/repos/amzxyz/RIME-LMDG/releases/tags/{GRAMMAR_TAG}"
)
GRAMMAR_URL = (
    f"https://github.com/amzxyz/RIME-LMDG/releases/download/{GRAMMAR_TAG}/{GRAMMAR_NAME}"
)


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
    headers = {"User-Agent": "Aegis-test-dict-fetch"}
    token = os.environ.get("GITHUB_TOKEN")
    parsed = urlsplit(url)
    request = urllib.request.Request(url, headers=headers)
    if token and parsed.scheme == "https" and parsed.hostname == "api.github.com":
        request.add_unredirected_header("Authorization", f"Bearer {token}")
    return urllib.request.urlopen(request, timeout=timeout)


def resolve_asset(manifest_url):
    with http_get(manifest_url, 60) as response:
        manifest = json.loads(response.read().decode("utf-8"))
    if manifest.get("schema_version") != 1 or manifest.get("kind") != "dictionary_update":
        raise SystemExit(f"unexpected dictionary manifest at {manifest_url}")
    asset = manifest["asset"]
    name = asset["name"]
    url = asset["url"]
    sha256 = normalize_sha256(asset["sha256"])
    expected_name = f"aegis_dict_pack_{DICT_LATEST_TAG}.zip"
    expected_url = (
        f"https://github.com/lurixo/Aegis/releases/download/{DICT_LATEST_TAG}/{expected_name}"
    )
    if name != expected_name or url != expected_url or sha256 is None:
        raise SystemExit("dictionary manifest does not describe the expected dict-latest pack")
    return url, sha256, name


def resolve_grammar_asset(release_api):
    with http_get(release_api, 60) as response:
        release = json.loads(response.read().decode("utf-8"))
    if release.get("tag_name") != GRAMMAR_TAG:
        raise SystemExit(f"unexpected grammar release at {release_api}")
    assets = [asset for asset in release.get("assets", []) if asset.get("name") == GRAMMAR_NAME]
    if len(assets) != 1:
        raise SystemExit(f"grammar release must carry exactly one {GRAMMAR_NAME}")
    asset = assets[0]
    sha256 = normalize_sha256(asset.get("digest"))
    size = asset.get("size")
    if asset.get("browser_download_url") != GRAMMAR_URL or sha256 is None:
        raise SystemExit("grammar release does not describe the expected LTS asset")
    if not isinstance(size, int) or size <= 1024:
        raise SystemExit("grammar release carries an invalid asset size")
    return GRAMMAR_URL, sha256, GRAMMAR_NAME, size


def download_to(url, dest, timeout):
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    part.unlink(missing_ok=True)
    try:
        with http_get(url, timeout) as response, part.open("wb") as out:
            shutil.copyfileobj(response, out, 1024 * 1024)
        part.replace(dest)
    except BaseException:
        part.unlink(missing_ok=True)
        raise


def ensure_pack(url, expected_sha256, zip_path, local_zip, timeout):
    if local_zip is not None:
        source = Path(local_zip)
        if not source.exists():
            raise SystemExit(f"--zip not found: {source}")
        actual = sha256_file(source)
        if actual != expected_sha256:
            raise SystemExit(f"--zip sha256 mismatch: {actual} != {expected_sha256}")
        return source
    if zip_path.exists():
        if sha256_file(zip_path) == expected_sha256:
            return zip_path
        zip_path.unlink()
    download_to(url, zip_path, timeout)
    actual = sha256_file(zip_path)
    if actual != expected_sha256:
        zip_path.unlink(missing_ok=True)
        raise SystemExit(f"downloaded pack sha256 mismatch: {actual} != {expected_sha256}")
    return zip_path


def ensure_grammar(url, expected_sha256, expected_size, grammar_path, timeout):
    if grammar_path.exists():
        if (
            grammar_path.stat().st_size == expected_size
            and sha256_file(grammar_path) == expected_sha256
        ):
            return grammar_path
        grammar_path.unlink()
    download_to(url, grammar_path, timeout)
    actual_size = grammar_path.stat().st_size
    actual_sha256 = sha256_file(grammar_path)
    if actual_size != expected_size or actual_sha256 != expected_sha256:
        grammar_path.unlink(missing_ok=True)
        raise SystemExit(
            "downloaded grammar mismatch: "
            f"size {actual_size} != {expected_size} or sha256 {actual_sha256} != {expected_sha256}"
        )
    return grammar_path


def target_for(entry_name):
    name = entry_name.replace("\\", "/").rsplit("/", 1)[-1].lower()
    if name == LM_NAME:
        return LM_NAME
    if name == EN_PACK_NAME:
        return EN_NAME
    if "jianpin" in name:
        return "aegis_jianpin.bin"
    if "t9" in name:
        return "aegis_t9.bin"
    if "dict" in name:
        return "aegis_dict.bin"
    return None


def extract_pack(zip_path, assets_dir, english_file=None):
    assets_dir.mkdir(parents=True, exist_ok=True)
    if english_file is not None:
        english_file.parent.mkdir(parents=True, exist_ok=True)
    expected = TEST_BINS if english_file is not None else RUNTIME_BINS
    selected = {}
    with zipfile.ZipFile(zip_path) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            target = target_for(entry.filename)
            if target is None:
                continue
            if target == EN_NAME and english_file is None:
                continue
            if target in selected:
                raise SystemExit(f"pack contains more than one entry for {target}")
            selected[target] = entry
        missing = [name for name in expected if name not in selected]
        if missing:
            raise SystemExit("pack is missing expected tables: " + ", ".join(missing))
        staged = {}
        parts = set()
        try:
            for target in expected:
                destination = english_file if target == EN_NAME else assets_dir / target
                part = destination.with_name(destination.name + ".part")
                part.unlink(missing_ok=True)
                parts.add(part)
                with archive.open(selected[target]) as source, part.open("wb") as out:
                    shutil.copyfileobj(source, out, 1024 * 1024)
                staged[target] = (destination, part, part.stat().st_size)
            small = [name for name in expected if staged[name][2] <= 1024]
            if small:
                raise SystemExit("pack tables are implausibly small: " + ", ".join(small))
            for target in expected:
                destination, part, _ = staged[target]
                part.replace(destination)
            return {
                target: (destination, size)
                for target, (destination, _, size) in staged.items()
            }
        finally:
            for part in parts:
                part.unlink(missing_ok=True)


def main(argv):
    repo_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(
        description=(
            "Fetch the full dict-latest dictionary pack and unpack its tables into the app assets "
            "and the English test-model directory so the quality tests run against current data."
        )
    )
    parser.add_argument("--manifest-url", default=MANIFEST_URL, help="Dictionary update manifest URL.")
    parser.add_argument(
        "--assets-dir",
        default=str(repo_root / "app" / "src" / "main" / "assets"),
        help="Directory that receives " + " / ".join(RUNTIME_BINS) + ".",
    )
    parser.add_argument(
        "--cache-dir",
        default=str(repo_root / "build" / "test-dict-cache"),
        help="Directory that keeps the downloaded pack between runs.",
    )
    parser.add_argument(
        "--english-file",
        default=str(repo_root / "build" / "test-models" / EN_NAME),
        help=f"Path that receives the real {EN_NAME} test table.",
    )
    parser.add_argument(
        "--grammar-release-api",
        default=GRAMMAR_RELEASE_API,
        help="GitHub API URL for the pinned grammar release tag.",
    )
    parser.add_argument(
        "--grammar-cache-dir",
        default=str(repo_root / "build" / "test-model-cache"),
        help="Directory that keeps the verified grammar model between runs.",
    )
    parser.add_argument("--zip", dest="local_zip", help="Use a local pack zip instead of downloading.")
    parser.add_argument("--timeout", type=int, default=300, help="Download timeout in seconds.")
    parser.add_argument(
        "--with-grammar",
        action="store_true",
        help=f"Also fetch and verify the current {GRAMMAR_TAG} {GRAMMAR_NAME} asset.",
    )
    print_group = parser.add_mutually_exclusive_group()
    print_group.add_argument(
        "--print-sha",
        action="store_true",
        help="Print the manifest pack sha256 and exit (for the CI cache key).",
    )
    print_group.add_argument(
        "--print-grammar-sha",
        action="store_true",
        help="Print the current LTS grammar sha256 and exit (for the CI cache key).",
    )
    args = parser.parse_args(argv)

    if args.print_grammar_sha:
        _, grammar_sha256, _, _ = resolve_grammar_asset(args.grammar_release_api)
        print(grammar_sha256)
        return 0

    url, expected_sha256, asset_name = resolve_asset(args.manifest_url)
    if args.print_sha:
        print(expected_sha256)
        return 0

    zip_path = ensure_pack(
        url, expected_sha256, Path(args.cache_dir) / asset_name, args.local_zip, args.timeout
    )
    assets_dir = Path(args.assets_dir)
    produced = extract_pack(zip_path, assets_dir, Path(args.english_file))
    print(f"dictionary pack {expected_sha256} unpacked into {assets_dir}")
    for name in TEST_BINS:
        path, size = produced[name]
        print(f"  {name}  {size} bytes  {path}")
    if args.with_grammar:
        grammar_url, grammar_sha256, grammar_name, grammar_size = resolve_grammar_asset(
            args.grammar_release_api
        )
        grammar_path = ensure_grammar(
            grammar_url,
            grammar_sha256,
            grammar_size,
            Path(args.grammar_cache_dir) / grammar_name,
            args.timeout,
        )
        print(
            f"grammar {grammar_sha256} verified at {grammar_path} "
            f"({grammar_path.stat().st_size} bytes)"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
