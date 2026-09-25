#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import verify_toolchain as vt


class PythonIdentityTest(unittest.TestCase):
    def test_supported_patch_versions_are_recorded_without_pinning(self):
        for patch in (4, 7):
            with self.subTest(patch=patch), mock.patch.object(
                vt.platform, "python_implementation", return_value="CPython"
            ), mock.patch.object(
                vt.platform, "python_version", return_value=f"3.14.{patch}"
            ), mock.patch.object(vt.sys, "version_info", (3, 14, patch)), mock.patch.object(
                vt.sys, "version", f"3.14.{patch} complete build identity"
            ):
                result = vt.python_identity()
                self.assertEqual(f"3.14.{patch}", result["version"])
                self.assertEqual(f"3.14.{patch} complete build identity", result["runtime"])
                self.assertEqual(64, len(result["executable_sha256"]))

    def test_wrong_implementation_or_family_is_rejected(self):
        for implementation, version in [("PyPy", (3, 14)), ("CPython", (3, 13)), ("CPython", (3, 15))]:
            with self.subTest(implementation=implementation, version=version), mock.patch.object(
                vt.platform, "python_implementation", return_value=implementation
            ), mock.patch.object(vt.sys, "version_info", version):
                with self.assertRaisesRegex(SystemExit, "CPython 3.14"):
                    vt.python_identity()


class JavaIdentityTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name) / "jdk"
        executable = self.home / "bin/java"
        executable.parent.mkdir(parents=True)
        executable.write_bytes(b"java fixture")
        self.patch = mock.patch.object(vt, "FIXED_JAVA_HOME", self.home)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def properties(self, version="25.0.4.1", runtime="25.0.4.1+1-1-26.04.4-Ubuntu"):
        return f"""    java.vendor = Ubuntu
    java.specification.version = 25
    java.version = {version}
    java.runtime.version = {runtime}
    java.home = {self.home}
"""

    def test_ubuntu_patch_and_build_changes_are_recorded(self):
        for version, runtime in [
            ("25.0.4", "25.0.4+7-1-26.04-Ubuntu"),
            ("25.0.4.1", "25.0.4.1+1-1-26.04.4-Ubuntu"),
            ("25.0.5", "25.0.5+9-Ubuntu"),
        ]:
            with self.subTest(runtime=runtime), mock.patch.object(
                vt.subprocess, "check_output", return_value=self.properties(version, runtime)
            ):
                result = vt.java_identity(vt.fixed_tool_environment())
                self.assertEqual(version, result["version"])
                self.assertEqual(runtime, result["runtime_version"])
                self.assertEqual("Ubuntu", result["vendor"])

    def test_wrong_or_incomplete_java_properties_are_rejected(self):
        original = self.properties()
        invalid = [
            original.replace("java.vendor = Ubuntu", "java.vendor = Other"),
            original.replace("java.specification.version = 25", "java.specification.version = 24"),
            original.replace("java.specification.version = 25", "java.specification.version = 26"),
            original.replace("    java.vendor = Ubuntu\n", ""),
            original.replace("    java.specification.version = 25\n", ""),
            original.replace(str(self.home), str(self.home / "other")),
            "unparseable output",
        ]
        for properties in invalid:
            with self.subTest(properties=properties), mock.patch.object(
                vt.subprocess, "check_output", return_value=properties
            ):
                with self.assertRaisesRegex(SystemExit, "Ubuntu JDK 25"):
                    vt.java_identity(vt.fixed_tool_environment())

    def test_inherited_jvm_overrides_are_cleared(self):
        names = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "TOOLS_OPTS", "GRADLE_OPTS")
        with mock.patch.dict(os.environ, {**dict.fromkeys(names, "override"), "JAVA_HOME": "/other"}):
            environment = vt.fixed_tool_environment()
        for name in names:
            self.assertNotIn(name, environment)
        self.assertEqual(str(self.home), environment["JAVA_HOME"])
        self.assertEqual(f"{self.home}/bin:/usr/bin:/bin", environment["PATH"])


class GradleIdentityTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        (self.repo / "gradle/wrapper").mkdir(parents=True)
        (self.repo / "gradlew").write_bytes(b"wrapper fixture")
        (self.repo / "gradle/wrapper/gradle-wrapper.jar").write_bytes(b"jar fixture")
        self.properties = self.repo / "gradle/wrapper/gradle-wrapper.properties"
        self.java = {
            "java_home": str(vt.FIXED_JAVA_HOME),
            "version": "25.0.4.1",
            "vendor": "Ubuntu",
            "runtime_version": "25.0.4.1+1-1-26.04.4-Ubuntu",
        }
        self.declare("9.7.1")

    def declare(self, version):
        self.properties.write_text(f"distributionUrl=https\\://services.gradle.org/distributions/gradle-{version}-bin.zip\n")

    def output(self, version="9.7.1", revision="a" * 40):
        return f"""Gradle {version}
Revision: {revision}
Launcher JVM: {self.java['version']} ({self.java['vendor']} {self.java['runtime_version']})
Daemon JVM: {self.java['java_home']} (using current Java home)
"""

    def identity(self, text):
        with mock.patch.object(vt.subprocess, "check_output", return_value=text):
            return vt.gradle_identity(self.repo, vt.fixed_tool_environment(), self.java)

    def test_version_follows_each_wrapper_and_revision_is_recorded(self):
        for version, revision in [("9.7.1", "a" * 40), ("9.8.0", "b" * 40)]:
            with self.subTest(version=version):
                self.declare(version)
                identity = self.identity(self.output(version, revision))
                self.assertEqual(version, identity["version"])
                self.assertEqual(revision, identity["revision"])
                self.assertEqual(3, len(identity["wrapper_files"]))
                self.assertEqual(vt.sha256_file(self.properties), identity["wrapper_files"][2]["sha256"])

    def test_missing_or_invalid_distribution_is_rejected_before_launch(self):
        for declaration in [
            "", "distributionUrl=invalid", "distributionUrl=https://example.com/gradle-9.7.1-bin.zip",
            "distributionUrl=https://services.gradle.org/distributions/gradle-9.8.0-rc-1-bin.zip",
            self.properties.read_text() * 2,
        ]:
            with self.subTest(declaration=declaration), mock.patch.object(vt.subprocess, "check_output") as launch:
                self.properties.write_text(declaration)
                with self.assertRaises(SystemExit):
                    vt.gradle_identity(self.repo, vt.fixed_tool_environment(), self.java)
                launch.assert_not_called()

    def test_mismatched_wrapper_or_jvm_is_rejected(self):
        text = self.output()
        for invalid in [
            self.output("9.8.0"),
            text.replace("Gradle 9.7.1", "no version"),
            text.replace("Revision:", "no revision:"),
            text.replace("Launcher JVM:", "no launcher:"),
            text.replace("Ubuntu", "Other"),
            text.replace("25.0.4.1 (", "25.0.3 ("),
            text.replace(self.java["java_home"], self.java["java_home"] + "-other"),
            text.replace("Daemon JVM:", "no daemon:"),
        ]:
            with self.subTest(output=invalid), self.assertRaisesRegex(SystemExit, "controlled JDK"):
                self.identity(invalid)


class EntrypointTest(unittest.TestCase):
    def test_cli_emits_the_shared_verified_identity_as_json(self):
        result = {"schema_version": 1, "python": {"version": "3.14.7"}}
        stdout = io.StringIO()
        with mock.patch.object(vt, "verify_toolchain", return_value=result) as verify, contextlib.redirect_stdout(stdout):
            self.assertEqual(0, vt.main(["--repository", "."]))
        verify.assert_called_once_with(Path("."))
        self.assertEqual(result, json.loads(stdout.getvalue()))


if __name__ == "__main__":
    unittest.main()
