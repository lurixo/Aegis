#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_test_dict as fd


class VersionedDictionaryDiscoveryTest(unittest.TestCase):
    def release(self, tag, published_at, digest_char="a"):
        names = [
            f"aegis_dict_pack_{tag}.zip",
            fd.UPDATE_ASSET_NAME,
            fd.BUILD_INFO_ASSET_NAME,
        ]
        return {
            "tag_name": tag,
            "html_url": f"{fd.RELEASE_URL_PREFIX}/{tag}",
            "draft": False,
            "prerelease": False,
            "published_at": published_at,
            "assets": [
                {
                    "name": name,
                    "size": 98_000_000 if name.endswith(".zip") else 800,
                    "digest": "sha256:" + digest_char * 64,
                    "browser_download_url": fd.expected_asset_url(tag, name),
                }
                for name in names
            ],
        }

    def manifest(self, release):
        return {
            "schema_version": 1,
            "kind": "dictionary_update",
            "asset": {
                "name": release["pack_name"],
                "url": release["pack_url"],
                "release_tag": release["tag"],
                "release_url": release["release_url"],
                "prerelease": False,
                "published_at": None,
                "sha256": release["pack_sha256"],
                "size_bytes": release["pack_size"],
            },
            "source": {
                "repo": fd.SOURCE_REPO,
                "ref_type": "tag",
                "tag": release["source_tag"],
                "branch": None,
                "commit": "1" * 40,
            },
        }

    def test_selects_unique_latest_published_release_independent_of_api_order(self):
        old = self.release("dict-v16.2.3-r1", "2026-07-25T01:00:00Z", "a")
        latest = self.release("dict-v16.2.4-r1", "2026-07-25T03:00:00Z", "b")
        middle = self.release("dict-v16.2.3-r2", "2026-07-25T02:00:00Z", "c")

        selected = fd.select_dictionary_release([latest, old, middle])

        self.assertEqual("dict-v16.2.4-r1", selected["tag"])
        self.assertEqual("v16.2.4", selected["source_tag"])
        self.assertEqual("b" * 64, selected["pack_sha256"])

    def test_rejects_tied_latest_release_times(self):
        releases = [
            self.release("dict-v16.2.3-r1", "2026-07-25T03:00:00Z"),
            self.release("dict-v16.2.4-r1", "2026-07-25T03:00:00Z", "b"),
        ]

        with self.assertRaisesRegex(ValueError, "tied"):
            fd.select_dictionary_release(releases)

    def test_frozen_rolling_release_is_not_a_discovery_fallback(self):
        rolling = self.release("dict-latest", "2026-07-25T04:00:00Z")

        with self.assertRaisesRegex(ValueError, "no eligible"):
            fd.select_dictionary_release([rolling])

    def test_rejects_an_incomplete_versioned_release(self):
        release = self.release("dict-v16.2.3-r1", "2026-07-25T03:00:00Z")
        release["assets"].pop()

        with self.assertRaisesRegex(ValueError, "exact dictionary asset set"):
            fd.select_dictionary_release([release])

    def test_manifest_must_match_the_selected_release(self):
        release = fd.select_dictionary_release(
            [self.release("dict-v16.2.3-r1", "2026-07-25T03:00:00Z")]
        )
        manifest = self.manifest(release)

        self.assertEqual(
            (release["pack_url"], release["pack_sha256"], release["pack_name"]),
            fd.dictionary_asset_from_manifest(manifest, release),
        )

        manifest["asset"]["release_tag"] = "dict-v16.2.3-r2"
        with self.assertRaisesRegex(ValueError, "does not match"):
            fd.dictionary_asset_from_manifest(manifest, release)


if __name__ == "__main__":
    unittest.main(verbosity=2)
