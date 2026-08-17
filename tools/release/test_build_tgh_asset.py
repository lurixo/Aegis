#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only
#

import contextlib
import hashlib
import io
import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_tgh_asset as bta

AUTHORITY = (
    Path(__file__).resolve().parents[2]
    / "app/src/main/assets-src/tongyong-guifan-hanzi-8105.tsv"
)

ASSET_BYTES = 10222
ASSET_SHA256 = "205013109d397b0905f17253b2552af02bec23ce67c61be6eb3a379349b60773"


def rows():
    return bta.read_authority(AUTHORITY)


class AuthorityTableTest(unittest.TestCase):
    def test_table_is_present_and_clean(self):
        self.assertTrue(AUTHORITY.is_file(), "missing authority table at %s" % AUTHORITY)
        self.assertEqual([], bta.check(rows()))

    def test_entry_count_is_8105(self):
        self.assertEqual(8105, bta.ENTRY_COUNT)
        self.assertEqual(bta.ENTRY_COUNT, len(rows()))

    def test_sequence_numbers_are_contiguous(self):
        self.assertEqual(list(range(1, bta.ENTRY_COUNT + 1)), [r[0] for r in rows()])

    def test_level_counts_are_3500_3000_1605(self):
        counts = {1: 0, 2: 0, 3: 0}
        for _, _, _, level in rows():
            counts[level] += 1
        self.assertEqual({1: 3500, 2: 3000, 3: 1605}, counts)
        self.assertEqual((3500, 3000, 1605), bta.LEVEL_COUNTS)

    def test_code_point_column_matches_the_character(self):
        for seq, char, codepoint, _ in rows():
            self.assertEqual(1, len(char), "seq %d is not a single code point" % seq)
            self.assertEqual("U+%04X" % ord(char), codepoint, "seq %d code point mismatch" % seq)

    def test_code_points_are_unique(self):
        code_points = [ord(r[1]) for r in rows()]
        self.assertEqual(len(code_points), len(set(code_points)))


class RejectsDriftTest(unittest.TestCase):
    def mutate(self, fn):
        data = rows()
        fn(data)
        return bta.check(data)

    def test_rejects_wrong_entry_count(self):
        errors = self.mutate(lambda d: d.pop())
        self.assertTrue(any("entry count 8104 != 8105" in e for e in errors), errors)

    def test_rejects_level_contradicting_its_band(self):
        def flip(d):
            seq, char, codepoint, _ = d[0]
            d[0] = (seq, char, codepoint, 2)

        errors = self.mutate(flip)
        self.assertTrue(any("level 2 contradicts band 1" in e for e in errors), errors)

    def test_rejects_broken_sequence(self):
        def skip(d):
            seq, char, codepoint, level = d[10]
            d[10] = (seq + 1000, char, codepoint, level)

        errors = self.mutate(skip)
        self.assertTrue(
            any("sequence 1011 at row 11 breaks 1..8105" in e for e in errors), errors
        )

    def test_rejects_duplicate_code_point(self):
        def duplicate(d):
            seq, _, _, level = d[5]
            _, char, codepoint, _ = d[0]
            d[5] = (seq, char, codepoint, level)

        errors = self.mutate(duplicate)
        self.assertTrue(any("repeats seq 1" in e for e in errors), errors)


class EncodingTest(unittest.TestCase):
    def test_round_trip_matches_the_source_levels(self):
        data = rows()
        table = bta.decode(bta.encode(data))
        expected = {int(codepoint[2:], 16): level for _, _, codepoint, level in data}
        self.assertEqual(expected, table)
        self.assertEqual(bta.ENTRY_COUNT, len(table))

    def test_header_is_magic_version_and_entry_count(self):
        blob = bta.encode(rows())
        self.assertEqual(b"AEGT", blob[:4])
        self.assertEqual(b"AEGT", bta.MAGIC)
        version, count = struct.unpack_from("<ii", blob, 4)
        self.assertEqual(bta.VERSION, version)
        self.assertEqual(bta.ENTRY_COUNT, count)

    def test_asset_size_and_sha256_are_stable(self):
        blob = bta.encode(rows())
        self.assertEqual(ASSET_BYTES, len(blob), "asset size drifted; update the ledger")
        self.assertEqual(
            ASSET_SHA256,
            hashlib.sha256(blob).hexdigest(),
            "asset bytes drifted; update the ledger",
        )

    def test_build_writes_the_asset(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "aegis_tgh.bin"
            with contextlib.redirect_stdout(io.StringIO()):
                blob = bta.build(AUTHORITY, out)
            self.assertEqual(ASSET_BYTES, out.stat().st_size)
            self.assertEqual(blob, out.read_bytes())
            self.assertEqual(ASSET_SHA256, hashlib.sha256(out.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main(verbosity=2)
