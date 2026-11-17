# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import re
import unittest
from pathlib import Path

from rtx_tools.benchmark import util

REPO_ROOT = util.PROJECT_DIR


def gradle_mod_version() -> str:
    for line in (REPO_ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("mod_version="):
            return line.split("=", 1)[1].strip()
    raise AssertionError("gradle.properties has no mod_version")


def neoforge_version() -> str:
    text = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
    match = re.search(r"def neoForgeVersion = '([^']+)'", text)
    if match is None:
        raise AssertionError("build.gradle has no pinned NeoForge version")
    return match.group(1)


def powershell_literal(path: Path, variable: str) -> str:
    text = path.read_text(encoding="utf-8")
    match = re.search(rf"\${re.escape(variable)}\s*=\s*'([^']+)'", text)
    if not match:
        raise AssertionError(f"{path} has no ${variable} string assignment")
    return match.group(1)


class VersionConsistencyTests(unittest.TestCase):
    def test_neoforge_loader_matches_the_gradle_configuration(self):
        build = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
        self.assertIn("id 'net.neoforged.moddev'", build)
        self.assertIn("version = neoForgeVersion", build)
        self.assertRegex(neoforge_version(), r"^21\\.1\\.\\d+$")
        self.assertNotIn("fabric-loom", build)

    def test_packaging_version_defaults_match_gradle(self):
        expected = gradle_mod_version()
        self.assertEqual(
            expected,
            powershell_literal(REPO_ROOT / "packaging" / "installer.ps1", "script:APP_VERSION"),
        )

    def test_runtime_dependency_versions_match_gradle(self):
        metadata = (REPO_ROOT / "src/main/resources/META-INF/neoforge.mods.toml").read_text(encoding="utf-8")
        self.assertIn('versionRange = "[1.21.1]"', metadata)
        self.assertIn('versionRange = "[${neoForgeVersion},)"', metadata)
        self.assertIn('version = "${version}"', metadata)
        self.assertEqual("21.1", ".".join(neoforge_version().split(".")[:2]))

    def test_runtime_code_does_not_hardcode_mod_version(self):
        benchmark_controller = (
            REPO_ROOT
            / "src"
            / "main"
            / "java"
            / "com"
            / "nvidia"
            / "rtxremix"
            / "minecraft"
            / "benchmark"
            / "BenchmarkController.java"
        ).read_text(encoding="utf-8")
        mod_entrypoint = (
            REPO_ROOT / "src" / "main" / "java" / "com" / "nvidia" / "rtxremix" / "minecraft" / "RtxRemixMod.java"
        ).read_text(encoding="utf-8")
        self.assertNotRegex(benchmark_controller, r'modVersion",\s*"\d+\.\d+\.\d+"')
        self.assertNotRegex(mod_entrypoint, r"Renderer v\d+\.\d+\.\d+")

    def test_neoforge_metadata_matches_repository_contract(self):
        metadata = (REPO_ROOT / "src/main/resources/META-INF/neoforge.mods.toml").read_text(encoding="utf-8")
        for expected in (
            'modLoader = "javafml"',
            'loaderVersion = "[4,)"',
            'license = "Apache-2.0"',
            'modId = "rtx_remix_renderer"',
            'modId = "neoforge"',
            'modId = "minecraft"',
            'config = "rtx-remix-renderer.mixins.json"',
            'file = "META-INF/accesstransformer.cfg"',
        ):
            with self.subTest(expected=expected):
                self.assertIn(expected, metadata)
        self.assertFalse((REPO_ROOT / "src/main/resources/fabric.mod.json").exists())

    def test_readme_license_matches_repository_license(self):
        license_text = (REPO_ROOT / "LICENSE").read_text(encoding="utf-8")
        readme = (REPO_ROOT / "README.md").read_text(encoding="utf-8")

        self.assertIn("Apache License", license_text)
        self.assertIn("Apache-2.0", readme)
        self.assertNotRegex(readme, r"(?m)^MIT\s+[\u2014-]\s+See LICENSE file$")


if __name__ == "__main__":
    unittest.main()
