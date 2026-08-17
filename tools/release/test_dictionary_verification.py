#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import importlib.util
import os
import shutil
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location("dictionary_verification", Path(__file__).with_name("dictionary_verification.py"))
verification = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verification)


class CoverageInputTest(unittest.TestCase):
    def baseline(self, root):
        rows = [f"{index}\t26\tfree\tshi\t-\t1\t{'1' * 64}" for index in range(1001)]
        header = ["# aegis-coverage-identity-v2"]
        header += [f"# asset-sha256 {name} {'0' * 64}" for name in verification.FETCH.RUNTIME_BINS]
        header += ["# canonical " + verification.CANONICAL, "# probes 1001", verification.COLUMNS]
        path = Path(root) / "coverage.tsv"
        path.write_text("\n".join(header + rows) + "\n")
        return path

    def test_existing_head_multiplicity_is_preserved(self):
        with tempfile.TemporaryDirectory() as root:
            path = self.baseline(root)
            self.assertEqual(set(verification.FETCH.RUNTIME_BINS), set(verification.coverage_metadata(path)))

    def test_malformed_headers_counts_ordinals_and_hashes_fail(self):
        for mutation in ("schema", "missing_asset", "duplicate_asset", "canonical", "count", "ordinal", "hash"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as root:
                path = self.baseline(root); lines = path.read_text().splitlines()
                if mutation == "schema": lines[0] = "# old-schema"
                elif mutation == "missing_asset": lines.pop(1)
                elif mutation == "duplicate_asset": lines.insert(1, lines[1])
                elif mutation == "canonical": lines[5] = "# canonical different"
                elif mutation == "count": lines[6] = "# probes 2"
                elif mutation == "ordinal": lines[9] = lines[8]
                else: lines[8] = lines[8].rsplit("\t", 1)[0] + "\tbad"
                path.write_text("\n".join(lines) + "\n")
                with self.assertRaises(SystemExit): verification.coverage_metadata(path)

    def test_baseline_component_mismatch_fails_before_materialization(self):
        with tempfile.TemporaryDirectory() as root:
            path = self.baseline(root)
            components = {name: {"sha256": "f" * 64} for name in verification.FETCH.RUNTIME_BINS}
            with mock.patch.object(verification.FETCH, "fixed_input_metadata", return_value=({}, components, ())), \
                 mock.patch.object(verification.FETCH, "main") as materialize:
                with self.assertRaisesRegex(SystemExit, "asset identities disagree"):
                    verification.prepare("pack", "manifest", "info", path)
            materialize.assert_not_called()

    def test_export_target_cannot_overwrite_or_land_in_the_repository(self):
        with tempfile.TemporaryDirectory() as root:
            repo = Path(root) / "repository"; repo.mkdir()
            existing = Path(root) / "existing.tsv"; existing.write_text("preserve")
            link = Path(root) / "alias.tsv"; link.symlink_to(repo / "baseline.tsv")
            for target in (existing, repo / "new.tsv", link):
                with self.subTest(target=target), self.assertRaises(SystemExit):
                    verification.output_path(target, repo)
            self.assertEqual("preserve", existing.read_text())
            self.assertEqual(Path(root) / "new.tsv", verification.output_path(Path(root) / "new.tsv", repo))


class JunitGateTest(unittest.TestCase):
    def results(self, root, count=1000, writer=False, skipped=False, failed=False):
        suite = ET.Element("testsuite")
        method = "writeCoverageDigestWhenAsked" if writer else "everyCandidateKeepsTheKeyCountItAteInTheBaseline"
        gate = ET.SubElement(suite, "testcase", classname="com.aegis.ime.decoder.CoverageIdentityGateTest", name=method)
        if skipped: ET.SubElement(gate, "skipped")
        if failed: ET.SubElement(gate, "failure")
        if not writer:
            ET.SubElement(suite, "testcase", classname="com.aegis.ime.dict.BuildInfoJsonTest", name="schema")
            for index in range(max(0, count - 2)):
                ET.SubElement(suite, "testcase", classname="other", name=f"case{index}")
        ET.ElementTree(suite).write(Path(root) / "TEST-results.xml")

    def test_release_requires_a_thousand_tests_and_executed_coverage(self):
        for count, skipped, failed in ((999, False, False), (1000, True, False), (1000, False, True)):
            with self.subTest(count=count, skipped=skipped, failed=failed), tempfile.TemporaryDirectory() as root:
                self.results(root, count=count, skipped=skipped, failed=failed)
                with self.assertRaises(SystemExit): verification.check_results(root)
        with tempfile.TemporaryDirectory() as root:
            self.results(root)
            verification.check_results(root)

    def test_writer_requires_exactly_its_unskipped_test(self):
        with tempfile.TemporaryDirectory() as root:
            self.results(root, writer=True, skipped=True)
            with self.assertRaises(SystemExit): verification.check_results(root, writer=True)
            self.results(root, writer=True)
            verification.check_results(root, writer=True)

    def test_release_rejects_missing_or_skipped_metadata_tests(self):
        with tempfile.TemporaryDirectory() as root:
            self.results(root)
            path = Path(root) / "TEST-results.xml"; tree = ET.parse(path)
            case = tree.getroot().findall("testcase")[1]
            ET.SubElement(case, "skipped"); tree.write(path)
            with self.assertRaisesRegex(SystemExit, "build-info tests"):
                verification.check_results(root)


class ShellSdkTest(unittest.TestCase):
    def fixture(self, root):
        root = Path(root)
        repository = root / "repository"
        release = repository / "tools/release"
        release.mkdir(parents=True)
        script = release / "verify_dictionary_release.sh"
        shutil.copyfile(Path(__file__).with_name(script.name), script)
        commands = root / "commands"; commands.mkdir()
        python = commands / "python3"
        python.write_text("#!/bin/sh\nexit 0\n"); python.chmod(0o755)
        gradle = repository / "gradlew"
        gradle.write_text("#!/bin/sh\nexit 0\n"); gradle.chmod(0o755)
        reports = repository / "app/build/outputs/mapping/release"
        reports.mkdir(parents=True)
        for name in ("configuration", "mapping", "seeds", "usage"):
            (reports / (name + ".txt")).write_text("report")
        inputs = []
        for name in ("pack.zip", "manifest.json", "build-info.json", "coverage.tsv"):
            path = root / name; path.write_text("input"); inputs.append(str(path))
        sdk = root / "android-sdk"
        manager = sdk / "cmdline-tools/latest/bin/sdkmanager"
        manager.parent.mkdir(parents=True)
        manager.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$SDK_TEST_ARGUMENTS"\nprintf "Installed packages\\n"\n')
        manager.chmod(0o755)
        environment = dict(os.environ, PATH=str(commands) + ":/usr/bin:/bin", SDK_TEST_ARGUMENTS=str(root / "sdk-arguments.txt"))
        environment.pop("ANDROID_HOME", None); environment.pop("ANDROID_SDK_ROOT", None)
        return script, inputs, sdk, manager, environment

    def run_script(self, script, inputs, environment):
        return subprocess.run(["bash", str(script), *inputs], env=environment, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def test_sdk_cli_does_not_need_to_be_on_path_and_receives_the_selected_root(self):
        for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
            with self.subTest(variable=variable), tempfile.TemporaryDirectory() as root:
                script, inputs, sdk, manager, environment = self.fixture(root)
                environment[variable] = str(sdk)
                result = self.run_script(script, inputs, environment)
                self.assertEqual(0, result.returncode, result.stdout)
                self.assertEqual([f"--sdk_root={sdk}", "--list_installed"],
                                 Path(environment["SDK_TEST_ARGUMENTS"]).read_text().splitlines())
                self.assertEqual("Installed packages\n", (script.parents[2] / "build/android-sdk-packages.txt").read_text())

    def test_symbolic_link_aliases_identify_the_same_sdk_root(self):
        with tempfile.TemporaryDirectory() as root:
            script, inputs, sdk, manager, environment = self.fixture(root)
            alias = Path(root) / "sdk-alias"; alias.symlink_to(sdk, target_is_directory=True)
            environment.update(ANDROID_HOME=str(alias), ANDROID_SDK_ROOT=str(sdk))
            result = self.run_script(script, inputs, environment)
            self.assertEqual(0, result.returncode, result.stdout)
            self.assertIn(f"--sdk_root={sdk}", Path(environment["SDK_TEST_ARGUMENTS"]).read_text())

    def test_missing_or_conflicting_sdk_inputs_fail_before_validation(self):
        for failure in ("unset", "missing_root", "conflict", "missing_cli"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as root:
                script, inputs, sdk, manager, environment = self.fixture(root)
                if failure == "missing_root": environment["ANDROID_HOME"] = str(Path(root) / "absent")
                elif failure == "conflict":
                    second = Path(root) / "other-sdk"; second.mkdir()
                    environment.update(ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(second))
                elif failure == "missing_cli":
                    environment["ANDROID_HOME"] = str(sdk); manager.unlink()
                result = self.run_script(script, inputs, environment)
                self.assertEqual(69, result.returncode, result.stdout)
                self.assertIn("SDK", result.stdout)
                self.assertFalse((script.parents[2] / "build").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
