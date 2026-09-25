#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import argparse
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
from pathlib import Path


FIXED_JAVA_HOME = Path("/usr/lib/jvm/java-25-openjdk-amd64")


def sha256_file(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def fixed_tool_environment():
    environment = dict(os.environ)
    for name in (
        "JAVA_HOME",
        "JAVA_TOOL_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "_JAVA_OPTIONS",
        "JAVA_OPTS",
        "TOOLS_OPTS",
        "GRADLE_OPTS",
    ):
        environment.pop(name, None)
    environment.update(
        {
            "JAVA_HOME": str(FIXED_JAVA_HOME),
            "PATH": f"{FIXED_JAVA_HOME}/bin:/usr/bin:/bin",
            "LANG": "C.UTF-8",
            "LC_ALL": "C.UTF-8",
            "TZ": "UTC",
        }
    )
    return environment


def python_identity():
    implementation = platform.python_implementation()
    version = platform.python_version()
    if implementation != "CPython" or sys.version_info[:2] != (3, 14):
        raise SystemExit(f"CPython 3.14 is required: {implementation} {version}")
    return {
        "implementation": implementation,
        "version": version,
        "runtime": sys.version,
        "executable_sha256": sha256_file(Path(sys.executable).resolve()),
    }


def java_identity(environment):
    executable = FIXED_JAVA_HOME / "bin/java"
    if FIXED_JAVA_HOME.is_symlink() or executable.is_symlink() or not executable.is_file():
        raise SystemExit(f"controlled Java runtime is unavailable: {executable}")
    text = subprocess.check_output(
        [str(executable), "-XshowSettings:properties", "-version"],
        env=environment,
        stderr=subprocess.STDOUT,
        text=True,
    )
    properties = dict(re.findall(r"^\s*(java\.[\w.]+) = (.+)$", text, re.MULTILINE))
    if (
        properties.get("java.vendor") != "Ubuntu"
        or properties.get("java.specification.version") != "25"
        or properties.get("java.home") != str(FIXED_JAVA_HOME)
        or not properties.get("java.version")
        or not properties.get("java.runtime.version")
    ):
        raise SystemExit("Ubuntu JDK 25 is required at the controlled Java home")
    return {
        "java_home": str(FIXED_JAVA_HOME),
        "java_executable_sha256": sha256_file(executable),
        "vendor": properties["java.vendor"],
        "specification_version": properties["java.specification.version"],
        "version": properties["java.version"],
        "runtime_version": properties["java.runtime.version"],
    }


def wrapper_version(repo_root):
    properties = repo_root / "gradle/wrapper/gradle-wrapper.properties"
    values = re.findall(r"^distributionUrl=(.+)$", properties.read_text(), re.MULTILINE)
    if len(values) != 1:
        raise SystemExit("Gradle wrapper must declare one distributionUrl")
    url = values[0].strip().replace("\\:", ":")
    match = re.fullmatch(
        r"https://(?:services|downloads)\.gradle\.org/distributions/gradle-([0-9]+(?:\.[0-9]+)+)-(?:bin|all)\.zip",
        url,
    )
    if match is None:
        raise SystemExit("Gradle wrapper distributionUrl must identify an official stable distribution")
    return match.group(1)


def gradle_identity(repo_root, environment, java):
    wrapper_files = [
        repo_root / "gradlew",
        repo_root / "gradle/wrapper/gradle-wrapper.jar",
        repo_root / "gradle/wrapper/gradle-wrapper.properties",
    ]
    if any(path.is_symlink() or not path.is_file() for path in wrapper_files):
        raise SystemExit("Gradle wrapper files are unavailable or unsafe")
    expected = wrapper_version(repo_root)
    text = subprocess.check_output(
        [str(repo_root / "gradlew"), "--version"],
        cwd=repo_root,
        env=environment,
        text=True,
    )
    version = re.search(r"^Gradle ([^\n]+)$", text, re.MULTILINE)
    revision = re.search(r"^Revision:\s+([0-9a-f]+)$", text, re.MULTILINE)
    launcher = re.search(r"^Launcher JVM:\s+(.+)$", text, re.MULTILINE)
    daemon = re.search(r"^Daemon JVM:\s+(.+)$", text, re.MULTILINE)
    expected_launcher = f"{java['version']} ({java['vendor']} {java['runtime_version']})"
    if (
        version is None
        or version.group(1) != expected
        or revision is None
        or launcher is None
        or launcher.group(1) != expected_launcher
        or daemon is None
        or daemon.group(1).split(" (", 1)[0] != java["java_home"]
    ):
        raise SystemExit("Gradle must match its wrapper and run on the controlled JDK")
    return {
        "version": version.group(1),
        "revision": revision.group(1),
        "launcher_jvm": launcher.group(1),
        "daemon_jvm": daemon.group(1),
        "wrapper_files": [
            {
                "path": str(path.relative_to(repo_root)),
                "size_bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }
            for path in wrapper_files
        ],
    }


def verify_toolchain(repo_root, environment=None):
    repo_root = Path(repo_root).resolve()
    environment = fixed_tool_environment() if environment is None else environment
    python = python_identity()
    java = java_identity(environment)
    return {
        "schema_version": 1,
        "python": python,
        "java": java,
        "gradle": gradle_identity(repo_root, environment, java),
        "environment": {
            key: environment[key]
            for key in ("JAVA_HOME", "PATH", "LANG", "LC_ALL", "TZ")
        },
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description="Verify the supported Python, Java, and wrapper toolchain.")
    parser.add_argument("--repository", type=Path, required=True)
    args = parser.parse_args(argv)
    print(json.dumps(verify_toolchain(args.repository), sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
