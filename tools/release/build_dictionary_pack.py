#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import hashlib
import os
import re
import shutil
import struct
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path

TABLES = [
    "zi",
    "jichu",
    "lianxiang",
    "cuoyin",
    "duoyin",
    "shici",
    "diming",
    "yixue",
    "huaxue",
    "yaopin",
    "mingren",
    "yiren",
    "wuzhong",
    "renming",
]

OUTPUTS = [
    ("aegis_dict_full.bin", "aegis_dict.bin", "letter"),
    ("aegis_t9_full.bin", "aegis_t9.bin", "digit"),
    ("aegis_jianpin_full.bin", "aegis_jianpin.bin", "initials"),
]

NOTICE_NAME = "NOTICE.txt"
LM_ENTRY = "aegis_lm.bin"
LM_MIN_BIGRAM = 1
PACK_ENTRIES = [NOTICE_NAME] + [item[0] for item in OUTPUTS] + [LM_ENTRY]
DANGEROUS_NEW_ENTRY_SUBSTRINGS = ("dict", "t9", "jianpin")
GRAMMAR_NAME = "wanxiang-lts-zh-hans.gram"
GRAMMAR_REPO_HTTPS = "https://github.com/amzxyz/RIME-LMDG"

def attribution_text(repo_https, source_tag, source_branch, source_commit):
    """The pack's third-party attribution, deterministic (no timestamps — only the pinned source
    coordinates vary), so the ZIP stays byte-reproducible. ASCII-only for maximum unzip compatibility.
    Wording mirrors the repository README's acknowledgments."""
    ref = f"tag {source_tag}" if source_tag else f"branch {source_branch}"
    tables = " ".join(TABLES)
    return (
        "Aegis downloadable dictionary pack - third-party attribution\n"
        "===========================================================\n"
        "\n"
        "This pack contains dictionary data DERIVED FROM the rime-wanxiang project.\n"
        "\n"
        "  Project:   rime-wanxiang\n"
        "  Copyright: (C) amzxyz and the rime-wanxiang contributors\n"
        "  License:   Creative Commons Attribution 4.0 International (CC BY 4.0)\n"
        "             https://creativecommons.org/licenses/by/4.0/\n"
        f"  Upstream:  {repo_https}\n"
        f"             {ref}, commit {source_commit}\n"
        "\n"
        f"  Source tables (14): {tables}\n"
        "\n"
        "Modifications by Aegis:\n"
        "  - tones stripped (the u-umlaut is written as v) and syllables concatenated\n"
        "    into toneless keys;\n"
        "  - repacked from the source .dict.yaml tables into Aegis's own binary format\n"
        "    (aegis_dict.bin / aegis_t9.bin / aegis_jianpin.bin);\n"
        "  - derived character unigram and bigram statistics into the AEGL v1\n"
        "    language model (aegis_lm.bin);\n"
        "  - this full pack keeps every entry (min-freq 1).\n"
        "\n"
        "CC BY 4.0 requires this attribution to accompany the material. Aegis's own code\n"
        "is licensed GPL-3.0-only; this notice concerns the bundled third-party dictionary\n"
        "data only. The repository's THIRD_PARTY_LICENSES.md carries the full license texts.\n"
    )


def run(cmd, cwd, env=None):
    print("+", " ".join(str(part) for part in cmd), flush=True)
    subprocess.run(cmd, cwd=cwd, env=env, check=True)


def output(cmd, cwd, env=None):
    return subprocess.check_output(cmd, cwd=cwd, env=env, text=True).strip()


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def require_safe_new_entry_names():
    names = [LM_ENTRY]
    if len(names) != len(set(names)):
        raise ValueError("downloadable component entry names must be unique")
    for name in names:
        lowered = name.lower()
        dangerous = [part for part in DANGEROUS_NEW_ENTRY_SUBSTRINGS if part in lowered]
        if dangerous:
            raise ValueError(f"unsafe downloadable component entry {name!r}: contains {dangerous}")

def path_in_repo(repo_root, relative):
    target = Path(os.path.normpath(repo_root / relative))
    if repo_root not in target.parents:
        raise ValueError(f"git reported a path outside the repository: {relative!r}")
    return target


def tree_dirt(repo_root):
    fields = subprocess.check_output(
        ["git", "status", "--porcelain", "-z", "--untracked-files=all"],
        cwd=repo_root,
        text=True,
    ).split("\0")
    rows = []
    index = 0
    while index < len(fields):
        entry = fields[index]
        index += 1
        if not entry:
            continue
        row = {"status": entry[:2], "path": entry[3:]}
        if "R" in entry[:2] or "C" in entry[:2]:
            row["renamed_from"] = fields[index]
            index += 1
        target = path_in_repo(repo_root, row["path"])
        stat = target.stat() if target.is_file() else None
        row["sha256"] = sha256_file(target) if stat else None
        row["size_bytes"] = stat.st_size if stat else None
        rows.append(row)
    return rows


def default_asset_name(release_tag):
    match = re.fullmatch(r"v\d+\.\d+\.\d+-debug\.(\d+)", release_tag)
    if match:
        return f"aegis_dict_pack_debug{match.group(1)}.zip"
    safe = re.sub(r"[^A-Za-z0-9._-]+", "-", release_tag).strip("-")
    return f"aegis_dict_pack_{safe}.zip"


def grammar_reference(release):
    assets = [item for item in release.get("assets", []) if item.get("name") == GRAMMAR_NAME]
    if len(assets) != 1:
        raise ValueError(f"expected exactly one {GRAMMAR_NAME} asset")
    asset = assets[0]
    digest = asset.get("digest")
    if not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", digest):
        raise ValueError(f"{GRAMMAR_NAME} has no trustworthy SHA-256 digest")
    size = asset.get("size")
    if not isinstance(size, int) or size <= 0:
        raise ValueError(f"{GRAMMAR_NAME} has no valid size")
    tag = release.get("tag_name")
    if not isinstance(tag, str) or not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9._-]*", tag):
        raise ValueError(f"{GRAMMAR_NAME} names no plain release tag: {tag!r}")
    expected_asset_url = f"{GRAMMAR_REPO_HTTPS}/releases/download/{tag}/{GRAMMAR_NAME}"
    if asset.get("browser_download_url") != expected_asset_url:
        raise ValueError(
            f"{GRAMMAR_NAME} is not served from {expected_asset_url}: {asset.get('browser_download_url')!r}"
        )
    expected_release_url = f"{GRAMMAR_REPO_HTTPS}/releases/tag/{tag}"
    if release.get("html_url") != expected_release_url:
        raise ValueError(
            f"{GRAMMAR_NAME} is not released at {expected_release_url}: {release.get('html_url')!r}"
        )
    return {
        "kind": "grammar_model",
        "physical_asset": {
            "name": GRAMMAR_NAME,
            "url": asset["browser_download_url"],
            "release_tag": tag,
            "release_url": release["html_url"],
            "prerelease": bool(release.get("prerelease")),
            "published_at": asset.get("updated_at") or release.get("published_at"),
            "sha256": digest.removeprefix("sha256:").lower(),
            "size_bytes": size,
            "github_asset_id": asset.get("id"),
        },
        "source": {
            "repo": GRAMMAR_REPO_HTTPS,
            "branch": None,
            "commit": None,
        },
        "attestation": {
            "status": "external_resource_not_attested_by_aegis",
        },
    }

def ensure_source_checkout(args, work_dir):
    if args.source_dir:
        source = Path(args.source_dir).resolve()
        if not source.exists():
            raise SystemExit(f"source dir does not exist: {source}")
        try:
            head = output(["git", "rev-parse", "--verify", "HEAD^{commit}"], cwd=source)
            tag_commit = (
                output(
                    ["git", "rev-parse", "--verify", f"{args.source_tag}^{{commit}}"],
                    cwd=source,
                )
                if args.source_tag
                else head
            )
            dirty = output(["git", "status", "--short"], cwd=source)
        except subprocess.CalledProcessError as error:
            raise SystemExit("source dir is not a valid checkout of the requested source tag") from error
        if dirty:
            raise SystemExit("source dir must be clean")
        if head != tag_commit:
            raise SystemExit(f"source dir HEAD does not match source tag {args.source_tag}")
        return source

    source = work_dir / "rime-wanxiang"
    if source.exists():
        shutil.rmtree(source)
    clone_ref = args.source_tag or args.source_branch
    run(
        [
            "git",
            "clone",
            "--depth",
            "1",
            "--branch",
            clone_ref,
            args.source_repo,
            str(source),
        ],
        cwd=work_dir,
    )
    return source

def write_zip(zip_path, entries):
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for entry_name, file_path in entries:
            info = zipfile.ZipInfo(entry_name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            zf.writestr(info, file_path.read_bytes(), compresslevel=9)


def build_info(
    args,
    repo_root,
    source_commit,
    asset_name,
    zip_path,
    component_infos,
    source_infos,
    grammar_info,
    pack_state="intermediate",
    tooling=None,
):
    if not isinstance(tooling, dict) or tooling.get("schema_version") != 1:
        raise ValueError("fixed builder tooling identity is missing")
    release_url = f"https://github.com/lurixo/Aegis/releases/tag/{args.release_tag}"
    asset_url = f"https://github.com/lurixo/Aegis/releases/download/{args.release_tag}/{asset_name}"
    builder_commit = output(["git", "rev-parse", "HEAD"], cwd=repo_root)
    dirt = tree_dirt(repo_root)
    generated_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    return {
        "schema_version": 1,
        "schema_name": "aegis.resource-build-info",
        "generated_at": generated_at,
        "app": {
            "project": "Aegis",
            "repository": "https://github.com/lurixo/Aegis",
            "release_tag": args.release_tag,
        },
        "resources": [
            {
                "kind": "dictionary",
                "physical_asset": {
                    "name": asset_name,
                    "url": asset_url,
                    "release_tag": args.release_tag,
                    "release_url": release_url,
                    "prerelease": False,
                    "published_at": None,
                    "sha256": sha256_file(zip_path),
                    "size_bytes": zip_path.stat().st_size,
                },
                "source": {
                    "repo": args.source_repo_https,
                    "ref_type": "tag" if args.source_tag else "branch",
                    "tag": args.source_tag,
                    "branch": None if args.source_tag else args.source_branch,
                    "commit": source_commit,
                    "license": "CC-BY-4.0",
                    "attribution_file_in_pack": NOTICE_NAME,
                    "tables": TABLES,
                    "input_yaml_sha256": source_infos,
                },
                "build": {
                    "pack_state": pack_state,
                    "builder_path": "tools/src/main/kotlin/com/aegis/tools/DictBuilder.kt",
                    "builder_commit": builder_commit,
                    "builder_tree_dirty": bool(dirt),
                    "builder_tree_dirt": dirt,
                    "tooling": tooling,
                    "full_pack_parameters": {
                        "min_freq": 1,
                        "max_per_key": None,
                        "commands": [
                            "--out aegis_dict_full.bin --min-freq 1 --keytype letter --t2s-data tools/t2s-data",
                            "--out aegis_t9_full.bin --min-freq 1 --keytype digit --t2s-data tools/t2s-data",
                            "--out aegis_jianpin_full.bin --min-freq 1 --keytype initials --t2s-data tools/t2s-data",
                            f"lm --out {LM_ENTRY} --min-bigram {LM_MIN_BIGRAM} --t2s-data tools/t2s-data",
                        ],
                    },
                    "language_model": {
                        "format": "AEGL v1",
                        "min_bigram": LM_MIN_BIGRAM,
                    },
                    "t2s_data": {
                        "path": "tools/t2s-data",
                        "provenance": "tools/t2s-data/PROVENANCE.md",
                        "license": "Apache-2.0 for the OpenCC tables (tools/t2s-data/LICENSE-OpenCC)",
                        "effect": "traditional and variant forms merge into their simplified image with frequency merging",
                    },
                    "output_bins": component_infos,
                    "zip_packaging": {
                        "file_order": PACK_ENTRIES,
                        "timestamp_utc": "1980-01-01T00:00:00Z",
                        "unix_mode": "0644",
                        "compression": "zip_deflated_level_9",
                    },
                },
                "attestation": {
                    "status": "not_attested",
                    "reproducibility_status": "build_inputs_recorded_but_unsigned",
                    "missing": [
                        "signature or attestation",
                        "independent external rebuild verification",
                    ],
                },
            }
        ],
        "external_resource_references": [grammar_info],
    }


def update_payload(build_info_json):
    dictionary = build_info_json["resources"][0]
    return {
        "schema_version": 1,
        "kind": "dictionary_update",
        "asset": dictionary["physical_asset"],
        "source": {
            "repo": dictionary["source"]["repo"],
            "ref_type": dictionary["source"]["ref_type"],
            "tag": dictionary["source"]["tag"],
            "branch": dictionary["source"]["branch"],
            "commit": dictionary["source"]["commit"],
        },
    }


def require_aegl_v1(path):
    data = path.read_bytes()
    if len(data) < 20 or data[:4] != b"AEGL" or struct.unpack_from("<i", data, 4)[0] != 1:
        raise ValueError(f"language model is not AEGL v1: {path}")
    num_chars = struct.unpack_from("<i", data, 8)[0]
    total_unigrams = struct.unpack_from("<q", data, 12)[0]
    if num_chars <= 0 or total_unigrams <= 0:
        raise ValueError(f"invalid AEGL v1 header counts: {path}")
    char_codes_offset = 20
    unigram_counts_offset = char_codes_offset + num_chars * 4
    row_totals_offset = unigram_counts_offset + num_chars * 8
    row_starts_offset = row_totals_offset + num_chars * 8
    num_bigrams_offset = row_starts_offset + (num_chars + 1) * 4
    if num_bigrams_offset + 4 > len(data):
        raise ValueError(f"truncated AEGL v1 header arrays: {path}")
    num_bigrams = struct.unpack_from("<i", data, num_bigrams_offset)[0]
    if num_bigrams < 0:
        raise ValueError(f"invalid AEGL v1 bigram count: {path}")
    bigram_targets_offset = num_bigrams_offset + 4
    bigram_counts_offset = bigram_targets_offset + num_bigrams * 4
    if bigram_counts_offset + num_bigrams * 8 != len(data):
        raise ValueError(f"invalid AEGL v1 file extent: {path}")

    previous_code = -1
    unigram_sum = 0
    for index in range(num_chars):
        code = struct.unpack_from("<i", data, char_codes_offset + index * 4)[0]
        count = struct.unpack_from("<q", data, unigram_counts_offset + index * 8)[0]
        if not (code > previous_code and 0 <= code <= 0x10FFFF and not 0xD800 <= code <= 0xDFFF):
            raise ValueError(f"invalid AEGL v1 character index: {path}")
        if count <= 0:
            raise ValueError(f"invalid AEGL v1 unigram count: {path}")
        previous_code = code
        unigram_sum += count
        if unigram_sum > 0x7FFF_FFFF_FFFF_FFFF:
            raise ValueError(f"overflowing AEGL v1 unigram total: {path}")
    if unigram_sum != total_unigrams:
        raise ValueError(f"AEGL v1 unigram total mismatch: {path}")

    row_starts = [
        struct.unpack_from("<i", data, row_starts_offset + index * 4)[0]
        for index in range(num_chars + 1)
    ]
    if row_starts[0] != 0 or row_starts[-1] != num_bigrams:
        raise ValueError(f"invalid AEGL v1 row boundary: {path}")
    previous_start = 0
    for start in row_starts:
        if not previous_start <= start <= num_bigrams:
            raise ValueError(f"invalid AEGL v1 row index: {path}")
        previous_start = start
    for row in range(num_chars):
        start, end = row_starts[row : row + 2]
        total = struct.unpack_from("<q", data, row_totals_offset + row * 8)[0]
        if total < 0 or (start != end and total <= 0):
            raise ValueError(f"invalid AEGL v1 row total: {path}")
        previous_target = -1
        retained = 0
        for index in range(start, end):
            target = struct.unpack_from("<i", data, bigram_targets_offset + index * 4)[0]
            count = struct.unpack_from("<q", data, bigram_counts_offset + index * 8)[0]
            if not 0 <= target < num_chars or target <= previous_target:
                raise ValueError(f"invalid AEGL v1 bigram index: {path}")
            if count <= 0:
                raise ValueError(f"invalid AEGL v1 bigram count: {path}")
            previous_target = target
            retained += count
            if retained > 0x7FFF_FFFF_FFFF_FFFF:
                raise ValueError(f"overflowing AEGL v1 bigram row: {path}")
        if retained > total:
            raise ValueError(f"invalid AEGL v1 row denominator: {path}")
    return {
        "format": "AEGL v1",
        "char_count": num_chars,
        "bigram_count": num_bigrams,
        "total_unigram_count": total_unigrams,
    }
