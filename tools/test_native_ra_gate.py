"""Require testNativeRa to fail when its test JVM has no native resources."""
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MESSAGE = "Native RetroAchievements library required by testNativeRa but unavailable"


def main():
    with tempfile.TemporaryDirectory(prefix="nestlin-native-gate-") as tmp:
        directory = Path(tmp)
        reports = directory / "reports"
        init = directory / "without-native.gradle"
        init.write_text("""
gradle.projectsEvaluated {
    rootProject.tasks.named('testNativeRa', org.gradle.api.tasks.testing.Test) {
        def nativeResources = project.layout.buildDirectory.dir('resources/main').get().asFile
        classpath = classpath.filter { it != nativeResources }
        outputs.upToDateWhen { false }
        reports.junitXml.outputLocation.set(new File('%s'))
    }
}
""" % reports.as_posix().replace("'", "\\'"), encoding="utf-8")
        wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
        result = subprocess.run([
            str(wrapper), "testNativeRa", "--tests", "*RetroAchievementsServiceFactoryTest",
            "--init-script", str(init), "--warning-mode=fail",
            "-x", "buildNative", "-x", "fetchNativeRa", "-x", "copyNativeRa",
            "-x", "writeNativeRaManifest",
        ], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
        cases = [case for report in reports.glob("TEST-*.xml")
                 for case in ET.parse(report).getroot().iter("testcase")]
        failures = [failure for case in cases for failure in case.findall("failure")]
        gate_failed = any(MESSAGE in ET.tostring(failure, encoding="unicode") for failure in failures)
        if result.returncode == 0 or not gate_failed or any(case.find("skipped") is not None for case in cases):
            print(result.stdout[-8000:])
            print(result.stderr[-2000:])
            raise AssertionError("testNativeRa must fail its native gate, without skipping, when the library is absent")
        print("Missing-library regression passed: testNativeRa failed its native gate with no skips")


if __name__ == "__main__":
    sys.stdout.reconfigure(errors="backslashreplace")
    main()
