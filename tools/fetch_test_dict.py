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
from pathlib import Path

DICT_LATEST_TAG = "dict-latest"
MANIFEST_URL = (
    f"https://github.com/lurixo/Aegis/releases/download/{DICT_LATEST_TAG}/aegis-dictionary-update.json"
)
RUNTIME_BINS = ("aegis_dict.bin", "aegis_t9.bin", "aegis_jianpin.bin")


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
    request = urllib.request.Request(url, headers={"User-Agent": "Aegis-test-dict-fetch"})
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
            "Fetch the full dict-latest dictionary pack and unpack its tables into the app assets "
            "so the decode-quality unit tests run against the full dictionary."
        )
    )
    parser.add_argument("--manifest-url", default=MANIFEST_URL, help="Dictionary update manifest URL.")
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

    url, expected_sha256, asset_name = resolve_asset(args.manifest_url)
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
