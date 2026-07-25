#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only
#

import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_dictionary_pack as bp

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
        for table in bp.TABLES:
            self.assertIn(table, text, f"attribution must list source table '{table}'")

    def test_is_deterministic_and_ascii(self):
        self.assertEqual(self.notice(), self.notice())
        self.notice().encode("ascii")

    def test_branch_mode_is_named_when_no_tag_is_pinned(self):
        self.assertIn("branch wanxiang", self.notice(tag=None))
        self.assertNotIn("tag ", self.notice(tag=None))

    def test_notice_name_is_never_mistaken_for_a_runtime_bin(self):
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
            p.write_bytes(zip_entry.encode("utf-8") * 7)
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


class GrammarReferenceTest(unittest.TestCase):
    def test_records_the_exact_mutable_lts_asset_snapshot(self):
        release = {
            "tag_name": "LTS",
            "html_url": "https://github.com/amzxyz/RIME-LMDG/releases/tag/LTS",
            "prerelease": False,
            "published_at": "2026-07-23T13:20:00Z",
            "assets": [
                {
                    "id": 487206811,
                    "name": bp.GRAMMAR_NAME,
                    "browser_download_url": f"https://example.test/{bp.GRAMMAR_NAME}",
                    "updated_at": "2026-07-23T13:19:40Z",
                    "digest": "sha256:" + "a" * 64,
                    "size": 420012076,
                }
            ],
        }

        ref = bp.grammar_reference(release)
        asset = ref["physical_asset"]
        self.assertEqual("a" * 64, asset["sha256"])
        self.assertEqual(420012076, asset["size_bytes"])
        self.assertEqual(487206811, asset["github_asset_id"])
        self.assertEqual("2026-07-23T13:19:40Z", asset["published_at"])

    def test_rejects_a_snapshot_without_a_digest(self):
        release = {
            "tag_name": "LTS",
            "html_url": "https://example.test/LTS",
            "assets": [
                {
                    "name": bp.GRAMMAR_NAME,
                    "browser_download_url": "https://example.test/model",
                    "size": 1,
                }
            ],
        }
        with self.assertRaises(ValueError):
            bp.grammar_reference(release)


class VersionedDictionaryReleaseTest(unittest.TestCase):
    def test_release_tag_and_asset_name_preserve_the_upstream_version(self):
        self.assertEqual("v16.2.3", bp.dictionary_release_source_tag("dict-v16.2.3-r1"))
        self.assertEqual(
            "aegis_dict_pack_dict-v16.2.3-r1.zip",
            bp.default_asset_name("dict-v16.2.3-r1"),
        )

    def test_rejects_rolling_app_and_zero_revision_tags(self):
        for tag in ["dict-latest", "v0.1.0-beta.23", "dict-v16.2.3-r0"]:
            with self.assertRaises(ValueError):
                bp.dictionary_release_source_tag(tag)


if __name__ == "__main__":
    unittest.main(verbosity=2)
