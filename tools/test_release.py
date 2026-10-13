"""Release guards: run with python tools/test_release.py (no JDK needed)."""
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

import release


NOTES = """# Nestlin v0.2.0

## Highlights
Portable builds.
## Downloads
Choose your platform.
## Known limitations
Hobby emulator.
## Save compatibility
Save-state format version: 12.
Minimum readable save-state version: 4.
"""


class ReleaseNotesTest(unittest.TestCase):
    def test_current_save_contract_is_accepted(self):
        release.validate_notes("v0.2.0", NOTES, 12, 4)

    def test_stale_save_version_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "save-state format"):
            release.validate_notes("v0.2.0", NOTES.replace("version: 12", "version: 1"), 12, 4)

    def test_stale_minimum_version_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "minimum readable"):
            release.validate_notes("v0.2.0", NOTES.replace("version: 4", "version: 1"), 12, 4)

    def test_heading_without_save_contract_is_rejected(self):
        with self.assertRaises(ValueError):
            release.validate_notes("v0.2.0", NOTES.split("Save-state format")[0], 12, 4)

    def test_malformed_tags_are_rejected(self):
        for tag in ("latest", "v1.2", "v01.2.3", "0.2.0", "v0.2.0-rc1"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.validate_notes(tag, NOTES, 12, 4)

    def test_incomplete_notes_are_rejected(self):
        for text in (NOTES.replace("Portable builds.", "TODO"), NOTES.replace("## Downloads", "## Other"), ""):
            with self.subTest(text=text), self.assertRaises(ValueError):
                release.validate_notes("v0.2.0", text, 12, 4)

    def test_validate_reads_notes_and_production_versions_together(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            notes = root / "docs/releases/v0.2.0.md"
            source = root / "src/main/kotlin/com/github/alondero/nestlin/SaveState.kt"
            notes.parent.mkdir(parents=True)
            source.parent.mkdir(parents=True)
            notes.write_text(NOTES, encoding="utf-8")
            source.write_text("const val VERSION = 13\nprivate const val MIN_SUPPORTED_VERSION = 4", encoding="utf-8")
            with patch.object(release, "ROOT", root), self.assertRaisesRegex(ValueError, "save-state format"):
                release.validate("v0.2.0")

    def test_save_versions_come_from_production_source(self):
        source = "const val VERSION = 15\nprivate const val MIN_SUPPORTED_VERSION = 7"
        self.assertEqual((15, 7), release.save_versions(source))


class NativeReportTest(unittest.TestCase):
    def check_report(self, cases):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / "TEST-native.xml").write_text(f'<testsuite>{cases}</testsuite>', encoding="utf-8")
            return release.verify_native_tests(path)

    def test_missing_reports_fail(self):
        with tempfile.TemporaryDirectory() as tmp, self.assertRaisesRegex(ValueError, "no native test reports"):
            release.verify_native_tests(Path(tmp))

    def test_empty_report_fails(self):
        with self.assertRaisesRegex(ValueError, "zero"):
            self.check_report("")

    def test_all_disabled_tests_fail(self):
        with self.assertRaises(ValueError):
            self.check_report('<testcase name="native"><skipped/></testcase>')

    def test_partial_skip_fails_even_if_an_ungated_test_passes(self):
        with self.assertRaisesRegex(ValueError, "skipped"):
            self.check_report('<testcase name="constants"/><testcase name="native"><skipped/></testcase>')

    def test_test_failures_fail(self):
        for element in ("failure", "error"):
            with self.subTest(element=element), self.assertRaises(ValueError):
                self.check_report(f'<testcase><{element}/></testcase>')

    def test_executed_passing_tests_are_accepted(self):
        self.assertEqual(2, self.check_report('<testcase name="load"/><testcase name="login"/>'))


class PlayerMaterialTest(unittest.TestCase):
    def test_link_rewriter_keeps_bundled_links_and_resolves_repository_links(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "repo"
            image = Path(tmp) / "image"
            (root / "docs").mkdir(parents=True)
            (image / "docs").mkdir(parents=True)
            (image / "docs/CHEATS.md").touch()
            text = "[cheats](CHEATS.md#codes) [maintain](../RA_INTEGRATION.md#native) [external](https://example.com) [anchor](#controls) [spaces](../A%20B.md)"
            result = release.rewrite_links(text, root / "docs/USER_GUIDE.md", image / "docs/USER_GUIDE.md", root, "abc123")
            self.assertIn("[cheats](CHEATS.md#codes)", result)
            self.assertIn("https://github.com/alondero/nestlin/blob/abc123/RA_INTEGRATION.md#native", result)
            self.assertIn("[external](https://example.com)", result)
            self.assertIn("[anchor](#controls)", result)
            self.assertIn("/A%20B.md)", result)

    def test_missing_required_license_fails(self):
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(FileNotFoundError):
            release.copy_player_materials(Path(tmp) / "repo", Path(tmp) / "image", "abc123")

    def test_player_bundle_has_legal_files_and_only_player_docs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "repo"
            image = Path(tmp) / "image"
            for name in ("LICENSE", "MAPPER_SUPPORT.md", "native/rcheevos/LICENSE", "native/rcheevos/NOTICE", "docs/USER_GUIDE.md", "docs/TROUBLESHOOTING.md", "docs/CHEATS.md", "docs/RELEASING.md"):
                source = root / name
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("[cheats](CHEATS.md) [help](TROUBLESHOOTING.md)" if name == "docs/USER_GUIDE.md" else "material", encoding="utf-8")
            release.copy_player_materials(root, image, "abc123")
            self.assertTrue((image / "LICENSE").is_file())
            self.assertTrue((image / "RCHEEVOS-LICENSE").is_file())
            self.assertTrue((image / "RCHEEVOS-NOTICE").is_file())
            self.assertTrue((image / "docs/USER_GUIDE.md").is_file())
            self.assertFalse((image / "docs/RELEASING.md").exists())
            guide = (image / "docs/USER_GUIDE.md").read_text(encoding="utf-8")
            self.assertIn("[cheats](CHEATS.md)", guide)
            self.assertIn("[help](TROUBLESHOOTING.md)", guide)


if __name__ == "__main__":
    unittest.main()
