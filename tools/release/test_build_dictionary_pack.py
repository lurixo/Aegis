#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only
#

import contextlib
import hashlib
import io
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace

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

    def test_claims_no_dictionary_seed_inside_the_app(self):
        text = self.notice()
        self.assertIn("this full pack keeps every entry (min-freq 1).", text)
        self.assertNotIn("seed", text.lower(), "the app ships no dictionary, so the pack may not claim one")

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
    def release(self, tag="LTS", url=None):
        return {
            "tag_name": tag,
            "html_url": f"{bp.GRAMMAR_REPO_HTTPS}/releases/tag/{tag}",
            "prerelease": False,
            "published_at": "2026-07-23T13:20:00Z",
            "assets": [
                {
                    "id": 487206811,
                    "name": bp.GRAMMAR_NAME,
                    "browser_download_url": url
                    if url is not None
                    else f"{bp.GRAMMAR_REPO_HTTPS}/releases/download/{tag}/{bp.GRAMMAR_NAME}",
                    "updated_at": "2026-07-23T13:19:40Z",
                    "digest": "sha256:" + "a" * 64,
                    "size": 420012076,
                }
            ],
        }

    def test_records_the_exact_mutable_lts_asset_snapshot(self):
        ref = bp.grammar_reference(self.release())
        asset = ref["physical_asset"]
        self.assertEqual("a" * 64, asset["sha256"])
        self.assertEqual(420012076, asset["size_bytes"])
        self.assertEqual(487206811, asset["github_asset_id"])
        self.assertEqual("2026-07-23T13:19:40Z", asset["published_at"])
        self.assertEqual(f"{bp.GRAMMAR_REPO_HTTPS}/releases/download/LTS/{bp.GRAMMAR_NAME}", asset["url"])
        self.assertEqual("LTS", asset["release_tag"])

    def test_rejects_a_snapshot_without_a_digest(self):
        release = self.release()
        del release["assets"][0]["digest"]
        with self.assertRaises(ValueError):
            bp.grammar_reference(release)

    def test_rejects_an_asset_served_by_another_host(self):
        for url in [
            f"https://example.test/releases/download/LTS/{bp.GRAMMAR_NAME}",
            f"https://github.com.example.test/amzxyz/RIME-LMDG/releases/download/LTS/{bp.GRAMMAR_NAME}",
            f"https://github.com/attacker/RIME-LMDG/releases/download/LTS/{bp.GRAMMAR_NAME}",
        ]:
            with self.assertRaises(ValueError):
                bp.grammar_reference(self.release(url=url))

    def test_rejects_an_asset_url_that_is_not_https(self):
        for scheme in ["http", "ftp"]:
            url = f"{scheme}://github.com/amzxyz/RIME-LMDG/releases/download/LTS/{bp.GRAMMAR_NAME}"
            with self.assertRaises(ValueError):
                bp.grammar_reference(self.release(url=url))

    def test_rejects_an_asset_url_outside_the_release_download_form(self):
        for url in [
            f"{bp.GRAMMAR_REPO_HTTPS}/releases/download/{bp.GRAMMAR_NAME}",
            f"{bp.GRAMMAR_REPO_HTTPS}/raw/LTS/{bp.GRAMMAR_NAME}",
            f"{bp.GRAMMAR_REPO_HTTPS}/releases/download/LTS/somethingelse.gram",
            f"{bp.GRAMMAR_REPO_HTTPS}/releases/download/LTS/{bp.GRAMMAR_NAME}?host=example.test",
        ]:
            with self.assertRaises(ValueError):
                bp.grammar_reference(self.release(url=url))

    def test_rejects_a_release_tag_that_walks_out_of_the_repository(self):
        for tag in ["../../attacker/evil", ".."]:
            with self.assertRaises(ValueError):
                bp.grammar_reference(self.release(tag=tag))


class DefaultAssetNameTest(unittest.TestCase):
    def test_rolling_tag_keeps_the_name_the_installed_app_asks_for(self):
        self.assertEqual("aegis_dict_pack_dict-latest.zip", bp.default_asset_name("dict-latest"))

    def test_debug_tag_keeps_the_short_numbered_name(self):
        self.assertEqual("aegis_dict_pack_debug13.zip", bp.default_asset_name("v0.1.0-debug.13"))

    def test_a_dotted_tag_keeps_its_dots_so_two_versions_cannot_share_a_name(self):
        self.assertEqual("aegis_dict_pack_dict-v16.2.3.zip", bp.default_asset_name("dict-v16.2.3"))
        self.assertEqual("aegis_dict_pack_dict-v1.6.23.zip", bp.default_asset_name("dict-v1.6.23"))


class ManifestReleaseTypeTest(unittest.TestCase):
    def manifest(self, root):
        repo = root / "builder"
        repo.mkdir()
        for command in (
            ["init", "-q"],
            ["config", "user.name", "Test User"],
            ["config", "user.email", "test@example.com"],
            ["commit", "-qm", "Create builder", "--allow-empty"],
        ):
            subprocess.run(["git", *command], cwd=repo, check=True, capture_output=True, text=True)
        pack = root / "pack.zip"
        pack.write_bytes(b"pack")
        args = SimpleNamespace(
            release_tag="dict-latest",
            source_repo_https=REPO,
            source_tag="v16.3.0",
            source_branch="wanxiang",
        )
        return bp.build_info(args, repo, COMMIT, "aegis_dict_pack_dict-latest.zip", pack, [], [], {})

    def test_the_dictionary_asset_is_never_published_as_a_prerelease(self):
        with tempfile.TemporaryDirectory() as directory:
            info = self.manifest(Path(directory))

            self.assertIs(False, info["resources"][0]["physical_asset"]["prerelease"])
            self.assertIs(False, bp.update_payload(info)["asset"]["prerelease"])

    def test_no_command_line_flag_can_request_a_prerelease_manifest(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as raised:
                bp.main(["--release-tag", "dict-latest", "--prerelease"])

        self.assertEqual(2, raised.exception.code)


class BuilderTreeDirtTest(unittest.TestCase):
    def git(self, repo, *args):
        subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True, text=True)

    def builder(self, root):
        repo = root / "builder"
        repo.mkdir()
        self.git(repo, "init", "-q")
        self.git(repo, "config", "user.name", "Test User")
        self.git(repo, "config", "user.email", "test@example.com")
        for name in ("kept.txt", "changed.txt", "moved.txt", "removed.txt"):
            (repo / name).write_text(f"{name} original\n")
        self.git(repo, "add", "-A")
        self.git(repo, "commit", "-qm", "Create builder")
        return repo

    def build(self, repo, root):
        pack = root / "pack.zip"
        pack.write_bytes(b"pack")
        args = SimpleNamespace(
            release_tag="dict-latest",
            source_repo_https=REPO,
            source_tag="v16.3.0",
            source_branch="wanxiang",
        )
        info = bp.build_info(args, repo, COMMIT, "aegis_dict_pack_dict-latest.zip", pack, [], [], {})
        return info["resources"][0]["build"]

    def test_a_clean_builder_tree_reports_no_dirt_at_all(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build = self.build(self.builder(root), root)

            self.assertIs(False, build["builder_tree_dirty"])
            self.assertEqual([], build["builder_tree_dirt"], "a clean tree must not be described as dirty")

    def test_every_dirty_path_is_listed_with_its_working_tree_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = self.builder(root)
            (repo / "changed.txt").write_text("changed.txt overlay\n")
            self.git(repo, "mv", "moved.txt", "renamed.txt")
            (repo / "removed.txt").unlink()
            (repo / "untracked").mkdir()
            (repo / "untracked" / "added.txt").write_text("added\n")

            build = self.build(repo, root)
            rows = {row["path"]: row for row in build["builder_tree_dirt"]}

            self.assertIs(True, build["builder_tree_dirty"])
            self.assertEqual(
                {"changed.txt", "renamed.txt", "removed.txt", "untracked/added.txt"},
                set(rows),
                "every dirty path must be described, and no clean path may be",
            )
            self.assertEqual("moved.txt", rows["renamed.txt"]["renamed_from"])
            self.assertIsNone(rows["removed.txt"]["sha256"], "a deleted path has no working-tree content")
            self.assertIsNone(rows["removed.txt"]["size_bytes"])
            for path in ("changed.txt", "renamed.txt", "untracked/added.txt"):
                content = (repo / path).read_bytes()
                self.assertEqual(hashlib.sha256(content).hexdigest(), rows[path]["sha256"])
                self.assertEqual(len(content), rows[path]["size_bytes"])
            self.assertNotIn("kept.txt", rows)

    def test_a_working_tree_rename_is_described_as_one_row(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = self.builder(root)
            (repo / "moved.txt").rename(repo / "renamed.txt")
            self.git(repo, "add", "-N", "renamed.txt")

            rows = self.build(repo, root)["builder_tree_dirt"]

            self.assertEqual([" R"], [row["status"] for row in rows])
            self.assertEqual(
                ["renamed.txt"],
                [row["path"] for row in rows],
                "the from-path of a working-tree rename must not become a row of its own",
            )
            self.assertEqual("moved.txt", rows[0]["renamed_from"])
            content = (repo / "renamed.txt").read_bytes()
            self.assertEqual(hashlib.sha256(content).hexdigest(), rows[0]["sha256"])
            self.assertEqual(len(content), rows[0]["size_bytes"])

    def test_a_working_tree_copy_is_described_as_one_row(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = self.builder(root)
            self.git(repo, "config", "status.renames", "copies")
            (repo / "copied.txt").write_bytes((repo / "changed.txt").read_bytes())
            (repo / "changed.txt").write_text("changed.txt overlay\n")
            self.git(repo, "add", "-N", "copied.txt")

            rows = self.build(repo, root)["builder_tree_dirt"]

            self.assertEqual([" M", " C"], [row["status"] for row in rows])
            self.assertEqual(
                ["changed.txt", "copied.txt"],
                [row["path"] for row in rows],
                "the from-path of a working-tree copy must not become a row of its own",
            )
            self.assertEqual("changed.txt", rows[1]["renamed_from"])

    def test_a_path_outside_the_repository_is_refused_instead_of_hashed(self):
        repo = Path("/nonexistent/builder")

        self.assertEqual(repo / "app" / "kept.txt", bp.path_in_repo(repo, "app/kept.txt"))
        for outside in ("/etc/hostname", "app/../../etc/hostname"):
            with self.assertRaises(ValueError):
                bp.path_in_repo(repo, outside)


class SourceCheckoutValidationTest(unittest.TestCase):
    def git(self, repo, *args):
        subprocess.run(
            ["git", *args],
            cwd=repo,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )

    def repository(self, root):
        repo = root / "source"
        repo.mkdir()
        self.git(repo, "init", "-q")
        self.git(repo, "config", "user.name", "Test User")
        self.git(repo, "config", "user.email", "test@example.com")
        table = repo / "table.dict.yaml"
        table.write_text("first\n")
        self.git(repo, "add", "table.dict.yaml")
        self.git(repo, "commit", "-qm", "Create source")
        self.git(repo, "tag", "v16.2.3")
        return repo, table

    def args(self, repo, source_tag="v16.2.3"):
        return SimpleNamespace(source_dir=str(repo), source_tag=source_tag)

    def test_accepts_clean_source_dir_at_the_source_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, _ = self.repository(root)

            self.assertEqual(
                repo.resolve(),
                bp.ensure_source_checkout(self.args(repo), root / "work"),
            )

    def test_accepts_clean_source_dir_with_no_pinned_source_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, _ = self.repository(root)

            self.assertEqual(
                repo.resolve(),
                bp.ensure_source_checkout(self.args(repo, None), root / "work"),
            )

    def test_rejects_source_dir_whose_head_does_not_match_the_source_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, table = self.repository(root)
            table.write_text("second\n")
            self.git(repo, "add", "table.dict.yaml")
            self.git(repo, "commit", "-qm", "Change source")

            with self.assertRaisesRegex(SystemExit, "HEAD does not match"):
                bp.ensure_source_checkout(self.args(repo), root / "work")

    def test_rejects_dirty_source_dir(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, table = self.repository(root)
            table.write_text("dirty\n")

            with self.assertRaisesRegex(SystemExit, "must be clean"):
                bp.ensure_source_checkout(self.args(repo), root / "work")


if __name__ == "__main__":
    unittest.main(verbosity=2)
