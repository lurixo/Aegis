#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only
#
# Standalone tests for the dictionary-pack attribution + deterministic packaging.
# Run: python3 tools/release/test_build_dictionary_pack.py  (or: python3 -m unittest -v)

import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_dictionary_pack as bp  # noqa: E402

REPO = "https://github.com/amzxyz/rime-wanxiang"
COMMIT = "7db7c588fd5ea90c13e4bf1814d7dd7fa8a2effc"


class AttributionTextTest(unittest.TestCase):
    def notice(self, tag="v16.0.1", branch="wanxiang", commit=COMMIT):
        return bp.attribution_text(REPO, tag, branch, commit)

    def test_carries_the_cc_by_attribution_facts(self):
        text = self.notice()
        for needle in [
            "amzxyz",
            "rime-wanxiang",
            "CC BY 4.0",
            "https://creativecommons.org/licenses/by/4.0/",
            REPO,
            "tag v16.0.1",
            f"commit {COMMIT}",
            "Modifications by Aegis",
            "GPL-3.0-only",
        ]:
            self.assertIn(needle, text, f"attribution must state: {needle}")
        # every one of the 14 source tables is named
        for table in bp.TABLES:
            self.assertIn(table, text, f"attribution must list source table '{table}'")

    def test_is_deterministic_and_ascii(self):
        # Pure function of its inputs (no timestamps) -> the pack stays byte-reproducible.
        self.assertEqual(self.notice(), self.notice())
        # ASCII-only -> no unzip mojibake and byte-stable across platforms.
        self.notice().encode("ascii")

    def test_branch_mode_is_named_when_no_tag_is_pinned(self):
        self.assertIn("branch wanxiang", self.notice(tag=None))
        self.assertNotIn("tag ", self.notice(tag=None))

    def test_notice_name_is_never_mistaken_for_a_runtime_bin(self):
        # The app extractor maps zip entries to the 3 runtime bins by these keywords; the notice name must
        # match none of them, or extraction would overwrite a bin with the notice text.
        low = bp.NOTICE_NAME.lower()
        for keyword in ("dict", "t9", "jianpin"):
            self.assertNotIn(keyword, low, f"NOTICE name must not contain runtime keyword '{keyword}'")


class DeterministicPackWithNoticeTest(unittest.TestCase):
    def _pack(self, work: Path) -> Path:
        staging = work / "staging"
        staging.mkdir()
        notice = staging / bp.NOTICE_NAME
        notice.write_bytes(bp.attribution_text(REPO, "v16.0.1", "wanxiang", COMMIT).encode("utf-8"))
        entries = [(bp.NOTICE_NAME, notice)]
        for zip_entry, _runtime, _key in bp.OUTPUTS:
            p = staging / zip_entry
            p.write_bytes(zip_entry.encode("utf-8") * 7)  # deterministic stand-in bin bytes
            entries.append((zip_entry, p))
        out = work / "pack.zip"
        bp.write_zip(out, entries)
        return out

    def test_pack_is_byte_reproducible_with_the_notice_included(self):
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            self.assertEqual(
                self._pack(Path(a)).read_bytes(),
                self._pack(Path(b)).read_bytes(),
                "pack must be byte-identical across independent builds",
            )

    def test_notice_is_first_entry_with_fixed_1980_timestamp_and_correct_body(self):
        with tempfile.TemporaryDirectory() as a:
            z = self._pack(Path(a))
            with zipfile.ZipFile(z) as zf:
                names = zf.namelist()
                self.assertEqual(bp.NOTICE_NAME, names[0], "attribution must be the first entry")
                self.assertIn("aegis_dict_full.bin", names)
                self.assertIn("aegis_t9_full.bin", names)
                self.assertIn("aegis_jianpin_full.bin", names)
                info = zf.getinfo(bp.NOTICE_NAME)
                self.assertEqual((1980, 1, 1, 0, 0, 0), info.date_time, "deterministic 1980 timestamp")
                body = zf.read(bp.NOTICE_NAME).decode("utf-8")
                self.assertIn("CC BY 4.0", body)
                self.assertIn("amzxyz", body)


if __name__ == "__main__":
    unittest.main(verbosity=2)
