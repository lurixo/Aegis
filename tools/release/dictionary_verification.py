#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import importlib.util
from pathlib import Path
import re

SPEC = importlib.util.spec_from_file_location("fetch_test_dict", Path(__file__).resolve().parents[1] / "fetch_test_dict.py")
FETCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FETCH)
CANONICAL = "last coveredLen per word; words sorted by UTF-16; SHA-256 over BE32 UTF-8-length, UTF-8 word, BE32 coveredLen"
COLUMNS = "ordinal\tlayout\tmode\tinput\tcontext\tuniqueCandidates\tsha256"


def coverage_metadata(path):
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    if not lines or lines[0] != "# aegis-coverage-identity-v2":
        raise SystemExit("coverage baseline must use aegis-coverage-identity-v2")
    assets = {}
    index = 1
    while index < len(lines) and lines[index].startswith("# asset-sha256 "):
        fields = lines[index].split(" ")
        if (len(fields) != 4 or fields[2] not in FETCH.RUNTIME_BINS or fields[2] in assets
                or not re.fullmatch(r"[0-9a-f]{64}", fields[3])):
            raise SystemExit("invalid or duplicate coverage asset identity")
        assets[fields[2]] = fields[3]
        index += 1
    if set(assets) != set(FETCH.RUNTIME_BINS):
        raise SystemExit("coverage baseline must identify all four decoder assets")
    if lines[index:index + 1] != ["# canonical " + CANONICAL]:
        raise SystemExit("unsupported coverage canonicalization")
    index += 1
    if index >= len(lines) or not re.fullmatch(r"# probes [1-9][0-9]*", lines[index]):
        raise SystemExit("invalid coverage probe count")
    count = int(lines[index].split(" ")[2])
    index += 1
    if lines[index:index + 1] != [COLUMNS]:
        raise SystemExit("invalid coverage columns")
    rows = lines[index + 1:]
    if len(rows) != count or count <= 1000:
        raise SystemExit("coverage baseline row count mismatch or incomplete sweep")
    for ordinal, line in enumerate(rows):
        fields = line.split("\t")
        if (len(fields) != 7 or fields[0] != str(ordinal)
                or fields[1] not in {"26", "9"} or fields[2] not in {"free", "locked"}
                or not fields[3] or not fields[4] or not fields[5].isdigit()
                or not re.fullmatch(r"[0-9a-f]{64}", fields[6])):
            raise SystemExit(f"invalid coverage row {ordinal}")
    return assets


def prepare(pack, manifest, build_info, baseline=None, grammar_lock=None):
    asset, components, _ = FETCH.fixed_input_metadata(manifest, build_info)
    if baseline:
        assets = coverage_metadata(baseline)
        if assets != {name: components[name]["sha256"] for name in FETCH.RUNTIME_BINS}:
            raise SystemExit("coverage baseline and fixed pack asset identities disagree")
    FETCH.verify_fixed_pack(pack, asset, components)
    if grammar_lock is None:
        raise SystemExit("a new grammar lock path is required")
    FETCH.freeze_grammar(grammar_lock)
    return FETCH.main(["--zip", str(pack), "--manifest-file", str(manifest),
                       "--build-info", str(build_info), "--grammar-lock-file", str(grammar_lock), "--with-grammar"])
