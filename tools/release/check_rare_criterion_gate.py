#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only
#
"""Rare-single criterion gate, run on every dictionary roll.

The grading table and the dictionary pack roll independently and are deliberately
not version-bound: level(cp) is total, so no pack can make the lookup fail. What a
pack roll does move is the corpus ranking, and the bounded lift reads that ranking.
This gate re-checks, against the pack about to be published, that the lift still
admits exactly what it is supposed to admit: the out-of-table characters real users
type, and nothing that the corpus itself calls rare.

Exit code 0 = the pack may ship. Non-zero = the lift moved; adjudicate before shipping.

Usage:
    check_rare_criterion_gate.py --lm <aegis_lm.bin> --authority <tsv> [--json <out>]
"""

import argparse
import json
import struct
import sys

GENERAL_USE_CARDINALITY = 6500
RARE_EVIDENCE_CEILING = 100
AUTHORITY_ENTRY_COUNT = 8105

MUST_LIFT_COMMON = ["磺"]
MUST_LIFT_DIALECT = ["冇", "哋", "嘅", "奀", "屌", "氽", "沕", "脷"]
MUST_NOT_LIFT = [
    "𡒄", "𢙐", "𢽾", "𥬠", "𪨗", "𪮖", "𫍢", "𫏋",
    "𫗦", "𫘤", "𫚒", "𫚙", "𫛛", "𫛞", "𫛶", "𫛸",
]


def load_lm(path):
    with open(path, "rb") as handle:
        blob = handle.read()
    if blob[:4] != b"AEGL":
        raise SystemExit("%s: not an AEGL file" % path)
    version, count = struct.unpack_from("<ii", blob, 4)
    if version != 1:
        raise SystemExit("%s: unsupported lm version %d" % (path, version))
    if len(blob) < 20 + count * 12:
        raise SystemExit("%s: truncated, %d characters do not fit" % (path, count))
    code_points = struct.unpack_from("<%di" % count, blob, 20)
    unigrams = struct.unpack_from("<%dq" % count, blob, 20 + count * 4)
    ordered = sorted(zip(code_points, unigrams), key=lambda pair: (-pair[1], pair[0]))
    ranks = {chr(cp): rank for rank, (cp, _) in enumerate(ordered, 1)}
    counts = {chr(cp): value for cp, value in zip(code_points, unigrams)}
    return ranks, counts, count


def load_levels(path):
    levels = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("#") or not line.strip():
                continue
            _, char, _, level = line.rstrip("\n").split("\t")
            levels[char] = int(level)
    if len(levels) != AUTHORITY_ENTRY_COUNT:
        raise SystemExit(
            "%s: expected %d entries, got %d" % (path, AUTHORITY_ENTRY_COUNT, len(levels))
        )
    return levels


def evaluate(ranks, counts, levels):
    def lifted(char):
        return char not in levels and ranks.get(char, sys.maxsize) <= GENERAL_USE_CARDINALITY

    failures = []
    for char in MUST_LIFT_COMMON + MUST_LIFT_DIALECT:
        if char in levels:
            failures.append("%s: expected out-of-table, but the table now grades it" % char)
        elif char not in ranks:
            failures.append("%s: must be lifted but the lm does not carry it" % char)
        elif not lifted(char):
            failures.append(
                "%s: must be lifted but rank #%d > %d"
                % (char, ranks[char], GENERAL_USE_CARDINALITY)
            )
    for char in MUST_NOT_LIFT:
        if lifted(char):
            failures.append(
                "%s: must stay rare but rank #%d <= %d"
                % (char, ranks[char], GENERAL_USE_CARDINALITY)
            )

    all_lifted = [char for char in ranks if lifted(char)]
    by_evidence = sorted(
        (char for char in all_lifted if counts.get(char, 0) <= RARE_EVIDENCE_CEILING),
        key=lambda char: ranks[char],
    )
    if by_evidence:
        failures.append(
            "%d lifted characters have corpus count <= %d: %s"
            % (len(by_evidence), RARE_EVIDENCE_CEILING, " ".join(by_evidence[:12]))
        )

    must_lift_ranks = [ranks[c] for c in MUST_LIFT_COMMON + MUST_LIFT_DIALECT if c in ranks]
    must_not_ranks = [ranks[c] for c in MUST_NOT_LIFT if c in ranks]
    return {
        "threshold_rank": GENERAL_USE_CARDINALITY,
        "rare_evidence_ceiling": RARE_EVIDENCE_CEILING,
        "lifted_total": len(all_lifted),
        "lifted_below_evidence_ceiling": len(by_evidence),
        "worst_must_lift_rank": max(must_lift_ranks, default=0),
        "best_must_not_lift_rank": min(must_not_ranks, default=0),
        "failures": failures,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lm", required=True)
    parser.add_argument("--authority", required=True)
    parser.add_argument("--json")
    args = parser.parse_args()

    ranks, counts, lm_chars = load_lm(args.lm)
    levels = load_levels(args.authority)
    report = evaluate(ranks, counts, levels)
    report["lm_chars"] = lm_chars
    low = report["worst_must_lift_rank"]
    high = report["best_must_not_lift_rank"]
    report["safe_window"] = [low, high]

    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2, sort_keys=True)

    print("lm characters           %d" % lm_chars)
    print("lift threshold          rank <= %d" % GENERAL_USE_CARDINALITY)
    print("characters lifted       %d" % report["lifted_total"])
    print("worst must-lift rank    #%d" % low)
    print("best must-not-lift rank #%d" % high)
    print("safe window             #%d .. #%d" % (low, high))

    if report["failures"]:
        print("", file=sys.stderr)
        print("GATE FAILED", file=sys.stderr)
        for failure in report["failures"]:
            print("  " + failure, file=sys.stderr)
        return 1
    print("")
    print("GATE PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
