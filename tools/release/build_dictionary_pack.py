#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import shutil
import struct
import subprocess
import zipfile
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
PACK_ENTRIES = [NOTICE_NAME] + [item[0] for item in OUTPUTS] + [LM_ENTRY]
DANGEROUS_NEW_ENTRY_SUBSTRINGS = ("dict", "t9", "jianpin")

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

def require_safe_new_entry_names():
    names = [LM_ENTRY]
    if len(names) != len(set(names)):
        raise ValueError("downloadable component entry names must be unique")
    for name in names:
        lowered = name.lower()
        dangerous = [part for part in DANGEROUS_NEW_ENTRY_SUBSTRINGS if part in lowered]
        if dangerous:
            raise ValueError(f"unsafe downloadable component entry {name!r}: contains {dangerous}")

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
