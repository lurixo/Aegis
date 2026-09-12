#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import hashlib
import json
import os
import re
import shutil
import sys
import time
import urllib.request
import zipfile
from http.client import IncompleteRead
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit

RETRY_ATTEMPTS = 6
RETRY_BASE_DELAY_SECONDS = 2.0
RETRY_WINDOW_SECONDS = 120.0
RETRYABLE_STATUS = frozenset({404, 408, 425, 429, 500, 502, 503, 504})

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
BUILD_INFO_NAME = "aegis-build-info.json"
BUILD_INFO_SCHEMA = "aegis.resource-build-info"
PACK_NAME = f"aegis_dict_pack_{DICT_LATEST_TAG}.zip"
PACK_URL = f"https://github.com/lurixo/Aegis/releases/download/{DICT_LATEST_TAG}/{PACK_NAME}"


def normalize_sha256(value):
    if not isinstance(value, str):
        return None
    raw = value.strip().lower()
    if raw.startswith("sha256:"):
        raw = raw[len("sha256:"):]
    return raw if re.fullmatch(r"[0-9a-f]{64}", raw) else None


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def retryable(error):
    if isinstance(error, HTTPError):
        return error.code in RETRYABLE_STATUS
    return isinstance(error, (URLError, TimeoutError, OSError, IncompleteRead))


def with_retry(
    description,
    attempt,
    attempts=RETRY_ATTEMPTS,
    base_delay=RETRY_BASE_DELAY_SECONDS,
    window=RETRY_WINDOW_SECONDS,
    sleep=None,
    clock=None,
):
    sleep = sleep or time.sleep
    clock = clock or time.monotonic
    deadline = clock() + window
    last = None
    for index in range(attempts):
        remaining = deadline - clock()
        if remaining <= 0:
            break
        try:
            return attempt(remaining)
        except (HTTPError, URLError, TimeoutError, OSError, IncompleteRead) as error:
            if isinstance(error, HTTPError):
                error.close()
            if not retryable(error):
                raise SystemExit(f"{description} failed: {error}") from error
            last = error
            if index == attempts - 1:
                break
            remaining = deadline - clock()
            if remaining <= 0:
                break
            sleep(min(base_delay * (2 ** index), remaining))
    reason = last or "retry count or window exhausted before the first attempt"
    raise SystemExit(f"{description} failed after bounded retries: {reason}")


def open_once(url, timeout):
    headers = {"User-Agent": "Aegis-test-dict-fetch"}
    token = os.environ.get("GITHUB_TOKEN")
    parsed = urlsplit(url)
    request = urllib.request.Request(url, headers=headers)
    if token and parsed.scheme == "https" and parsed.hostname == "api.github.com":
        request.add_unredirected_header("Authorization", f"Bearer {token}")
    return urllib.request.urlopen(request, timeout=timeout)


def http_get(url, timeout, **retry):
    def attempt(remaining):
        with open_once(url, min(timeout, remaining)) as response:
            return response.read()

    return with_retry(f"GET {url}", attempt, **retry)


def resolve_asset(manifest_url, **retry):
    manifest = json.loads(http_get(manifest_url, 60, **retry).decode("utf-8"))
    if manifest.get("schema_version") != 1 or manifest.get("kind") != "dictionary_update":
        raise SystemExit(f"unexpected dictionary manifest at {manifest_url}")
    asset = manifest["asset"]
    name = asset["name"]
    url = asset["url"]
    sha256 = normalize_sha256(asset["sha256"])
    if name != PACK_NAME or url != PACK_URL or sha256 is None:
        raise SystemExit("dictionary manifest does not describe the expected dict-latest pack")
    return url, sha256, name


def resolve_grammar_asset(release_api, **retry):
    release = json.loads(http_get(release_api, 60, **retry).decode("utf-8"))
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


def grammar_lock_from_release(release):
    if not isinstance(release, dict) or release.get("tag_name") != GRAMMAR_TAG:
        raise SystemExit("unexpected grammar release for lock")
    entries = release.get("assets")
    if not isinstance(entries, list):
        raise SystemExit("grammar release must list its assets")
    assets = [a for a in entries if isinstance(a, dict) and a.get("name") == GRAMMAR_NAME]
    if len(assets) != 1:
        raise SystemExit("grammar release must carry exactly one matching asset")
    asset = assets[0]
    lock = {
        "schema_version": 1,
        "kind": "aegis.grammar-lock",
        "release_tag": GRAMMAR_TAG,
        "asset": {
            "name": GRAMMAR_NAME,
            "url": asset.get("browser_download_url"),
            "github_asset_id": asset.get("id"),
            "sha256": normalize_sha256(asset.get("digest")),
            "size_bytes": asset.get("size"),
        },
    }
    validate_grammar_lock(lock)
    return lock


def validate_grammar_lock(lock):
    if (not isinstance(lock, dict) or lock.get("schema_version") != 1
            or lock.get("kind") != "aegis.grammar-lock" or lock.get("release_tag") != GRAMMAR_TAG):
        raise SystemExit("invalid grammar lock schema")
    asset = lock.get("asset")
    if not isinstance(asset, dict):
        raise SystemExit("grammar lock must identify its asset")
    size = asset.get("size_bytes")
    asset_id = asset.get("github_asset_id")
    if (asset.get("name") != GRAMMAR_NAME or asset.get("url") != GRAMMAR_URL
            or not isinstance(asset.get("sha256"), str)
            or normalize_sha256(asset["sha256"]) != asset["sha256"]
            or isinstance(size, bool) or not isinstance(size, int) or size <= 1024
            or isinstance(asset_id, bool) or not isinstance(asset_id, int) or asset_id <= 0):
        raise SystemExit("invalid grammar lock asset identity")
    return asset["url"], asset["sha256"], asset["name"], size


def freeze_grammar(path, release_api=GRAMMAR_RELEASE_API):
    if Path(path).exists() or Path(path).is_symlink():
        raise SystemExit(f"grammar lock already exists: {path}")
    release = json.loads(http_get(release_api, 60).decode("utf-8"))
    lock = grammar_lock_from_release(release)
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        with target.open("x", encoding="utf-8") as output:
            output.write(json.dumps(lock, indent=2) + "\n")
    except FileExistsError as error:
        raise SystemExit(f"grammar lock already exists: {target}") from error
    return lock


def load_build_info(path):
    try:
        info = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError) as error:
        return None, f"{path} is unreadable: {error}"
    if not isinstance(info, dict) or info.get("schema_name") != BUILD_INFO_SCHEMA or info.get("schema_version") != 1:
        return None, f"{path} is not a version 1 {BUILD_INFO_SCHEMA} document"
    return info, None


def pinned_asset(info, kind, section):
    entries = info.get(section, [])
    if not isinstance(entries, list):
        return None
    for entry in entries:
        if isinstance(entry, dict) and entry.get("kind") == kind:
            asset = entry.get("physical_asset")
            return asset if isinstance(asset, dict) else {}
    return None


def pinned_pack(info):
    asset = pinned_asset(info, "dictionary", "resources")
    if asset is None:
        return None, "it describes no dictionary resource"
    sha256 = normalize_sha256(asset.get("sha256"))
    if asset.get("name") != PACK_NAME or asset.get("url") != PACK_URL or sha256 is None:
        return None, "its dictionary resource is not the expected dict-latest pack"
    return (PACK_URL, sha256, PACK_NAME), None


def pinned_grammar(info):
    asset = pinned_asset(info, "grammar_model", "external_resource_references")
    if asset is None:
        return None, "it references no grammar model"
    sha256 = normalize_sha256(asset.get("sha256"))
    size = asset.get("size_bytes")
    if asset.get("url") != GRAMMAR_URL or sha256 is None:
        return None, f"its grammar reference is not the expected {GRAMMAR_TAG} asset"
    if isinstance(size, bool) or not isinstance(size, int) or size <= 1024:
        return None, "its grammar reference carries an invalid asset size"
    return (GRAMMAR_URL, sha256, GRAMMAR_NAME, size), None


def note(message):
    print(f"[fetch_test_dict] {message}", file=sys.stderr)


def resolve_against_pin(label, build_info_path, read_pin, resolve_live):
    info, problem = load_build_info(build_info_path)
    if info is None:
        pin, pin_problem = None, problem
    else:
        pin, pin_problem = read_pin(info)
    if pin is None:
        note(f"{label}: no usable pin ({pin_problem})")
    try:
        live = resolve_live()
    except (SystemExit, ValueError, OSError) as error:
        if pin is None:
            raise
        note(f"{label}: the live channel is unusable ({error})")
        note(f"{label}: continuing on the supplied pin {pin[1]}")
        return pin
    if pin is not None and pin[1] != live[1]:
        note(f"{label}: resolved metadata selects {live[1]} instead of the supplied pin {pin[1]}")
    return live


def resolve_dictionary(manifest_url, build_info_path, **retry):
    return resolve_against_pin(
        "dictionary pack",
        build_info_path,
        pinned_pack,
        lambda: resolve_asset(manifest_url, **retry),
    )


def resolve_grammar(release_api, build_info_path, **retry):
    return resolve_against_pin(
        "grammar model",
        build_info_path,
        pinned_grammar,
        lambda: resolve_grammar_asset(release_api, **retry),
    )


def download_to(url, dest, timeout, **retry):
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    part.unlink(missing_ok=True)

    def attempt(remaining):
        try:
            with open_once(url, min(timeout, remaining)) as response, part.open("wb") as out:
                length = getattr(response, "headers", {}).get("Content-Length")
                expected_size = int(length) if length is not None else None
                copied = 0
                for chunk in iter(lambda: response.read(1024 * 1024), b""):
                    out.write(chunk)
                    copied += len(chunk)
                if expected_size is not None and copied < expected_size:
                    raise IncompleteRead(b"", expected_size - copied)
            part.replace(dest)
        finally:
            part.unlink(missing_ok=True)

    with_retry(f"download {url}", attempt, **retry)


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


def load_json_document(path):
    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON key: {key}")
            result[key] = value
        return result

    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=unique_pairs)
        if not isinstance(value, dict):
            raise ValueError("expected an object")
        return value
    except (OSError, ValueError) as error:
        raise SystemExit(f"invalid JSON input {path}: {error}") from error


def fixed_input_metadata(manifest_path, build_info_path):
    manifest = load_json_document(manifest_path)
    info = load_json_document(build_info_path)
    if manifest.get("schema_version") != 1 or manifest.get("kind") != "dictionary_update":
        raise SystemExit("unexpected fixed dictionary manifest schema")
    if info.get("schema_version") != 1 or info.get("schema_name") != BUILD_INFO_SCHEMA:
        raise SystemExit("unexpected fixed build-info schema")
    resources = info.get("resources")
    if not isinstance(resources, list):
        raise SystemExit("build-info resources must be a list")
    dictionaries = [r for r in resources if isinstance(r, dict) and r.get("kind") == "dictionary"]
    if len(dictionaries) != 1:
        raise SystemExit("build-info must describe exactly one dictionary")
    dictionary = dictionaries[0]
    asset = manifest.get("asset")
    if not isinstance(asset, dict) or asset != dictionary.get("physical_asset"):
        raise SystemExit("manifest and build-info dictionary asset identities disagree")
    size = asset.get("size_bytes")
    if (asset.get("name") != PACK_NAME or asset.get("url") != PACK_URL
            or asset.get("release_tag") != DICT_LATEST_TAG
            or asset.get("prerelease") is not False
            or not isinstance(asset.get("sha256"), str)
            or normalize_sha256(asset.get("sha256")) != asset.get("sha256")
            or isinstance(size, bool) or not isinstance(size, int) or size <= 1024):
        raise SystemExit("invalid fixed dictionary asset identity")
    source = manifest.get("source")
    recorded_source = dictionary.get("source")
    if not isinstance(source, dict) or not isinstance(recorded_source, dict):
        raise SystemExit("fixed inputs must identify the dictionary source")
    for key in ("repo", "ref_type", "tag", "branch", "commit"):
        if key not in source or key not in recorded_source or source[key] != recorded_source[key]:
            raise SystemExit(f"manifest and build-info source identities disagree: {key}")
    if not isinstance(source["commit"], str) or not re.fullmatch(r"[0-9a-f]{40}", source["commit"]):
        raise SystemExit("invalid dictionary source commit")
    build = dictionary.get("build")
    entries = build.get("output_bins") if isinstance(build, dict) else None
    if not isinstance(entries, list):
        raise SystemExit("build-info must identify every output component")
    expected_entries = {
        "aegis_dict.bin": "aegis_dict_full.bin",
        "aegis_t9.bin": "aegis_t9_full.bin",
        "aegis_jianpin.bin": "aegis_jianpin_full.bin",
        LM_NAME: LM_NAME,
        EN_NAME: EN_PACK_NAME,
    }
    components = {}
    for entry in entries:
        if not isinstance(entry, dict):
            raise SystemExit("invalid output component")
        name = entry.get("runtime_name")
        size = entry.get("size_bytes")
        if (not isinstance(name, str) or name not in expected_entries or name in components
                or entry.get("zip_entry") != expected_entries[name]
                or normalize_sha256(entry.get("sha256")) != entry.get("sha256")
                or not isinstance(entry.get("sha256"), str)
                or isinstance(size, bool) or not isinstance(size, int) or size <= 1024):
            raise SystemExit(f"invalid or duplicate output component: {name}")
        components[name] = entry
    if set(components) != set(TEST_BINS):
        raise SystemExit("build-info must identify all five runtime components")
    grammar_refs = info.get("external_resource_references")
    if not isinstance(grammar_refs, list) or sum(
        isinstance(r, dict) and r.get("kind") == "grammar_model" for r in grammar_refs
    ) != 1:
        raise SystemExit("build-info must identify exactly one grammar model")
    grammar, problem = pinned_grammar(info)
    if grammar is None:
        raise SystemExit(f"invalid fixed grammar identity: {problem}")
    grammar_asset = pinned_asset(info, "grammar_model", "external_resource_references")
    if grammar_asset.get("name") != GRAMMAR_NAME:
        raise SystemExit("invalid fixed grammar asset name")
    return asset, components, grammar


def verify_fixed_pack(pack_path, asset, components):
    pack_path = Path(pack_path)
    if not pack_path.is_file() or pack_path.name != asset["name"]:
        raise SystemExit("fixed pack filename does not match the supplied asset identity")
    if pack_path.stat().st_size != asset["size_bytes"] or sha256_file(pack_path) != asset["sha256"]:
        raise SystemExit("fixed pack size or sha256 mismatch")
    try:
        with zipfile.ZipFile(pack_path) as archive:
            names = archive.namelist()
            expected = {entry["zip_entry"] for entry in components.values()} | {"NOTICE.txt"}
            if len(names) != len(set(names)) or set(names) != expected:
                raise SystemExit("fixed pack must contain exactly the declared components and NOTICE.txt")
            for component in components.values():
                entry = archive.getinfo(component["zip_entry"])
                if entry.is_dir() or entry.file_size != component["size_bytes"]:
                    raise SystemExit(f"fixed pack component size mismatch: {entry.filename}")
                digest = hashlib.sha256()
                with archive.open(entry) as source:
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        digest.update(chunk)
                if digest.hexdigest() != component["sha256"]:
                    raise SystemExit(f"fixed pack component sha256 mismatch: {entry.filename}")
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        raise SystemExit(f"invalid fixed pack: {error}") from error
    return pack_path
