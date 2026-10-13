"""Validate release notes and build a portable, runtime-bundled Nestlin image."""
import argparse
import os
import platform
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import zipfile
from urllib.parse import quote, unquote
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


def save_versions(source):
    values = []
    for name in ("VERSION", "MIN_SUPPORTED_VERSION"):
        match = re.search(rf"^\s*(?:private\s+)?const val {name}\s*=\s*(\d+)\b", source, re.MULTILINE)
        if not match:
            raise ValueError(f"Cannot read SaveState.{name}")
        values.append(int(match.group(1)))
    return tuple(values)


def validate_notes(tag, text, save_version, minimum_version):
    if not re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag):
        raise ValueError("Use a stable semantic version tag: vX.Y.Z")
    if not text.splitlines() or text.splitlines()[0] != f"# Nestlin {tag}":
        raise ValueError("Release notes title must match tag")
    for heading in ("Highlights", "Downloads", "Known limitations", "Save compatibility"):
        if not re.search(rf"^## {heading}$", text, re.MULTILINE):
            raise ValueError(f"Release notes: missing {heading}")
    if re.search(r"\b(TODO|TBD)\b", text):
        raise ValueError("Complete release notes before tagging")
    compatibility = text.split("## Save compatibility", 1)[1].split("\n## ", 1)[0]
    for label, expected, description in (
        ("Save-state format version", save_version, "save-state format"),
        ("Minimum readable save-state version", minimum_version, "minimum readable"),
    ):
        values = re.findall(rf"^{label}: (\d+)\.$", compatibility, re.MULTILINE)
        if values != [str(expected)]:
            raise ValueError(f"Release notes {description} must match SaveState: '{label}: {expected}.'")


def validate(tag):
    # Reject malformed tags before using them in paths.
    if not re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag):
        raise ValueError("Use a stable semantic version tag: vX.Y.Z")
    notes = (ROOT / "docs/releases" / f"{tag}.md").read_text(encoding="utf-8")
    source = (ROOT / "src/main/kotlin/com/github/alondero/nestlin/SaveState.kt").read_text(encoding="utf-8")
    validate_notes(tag, notes, *save_versions(source))


def verify_native_tests(report_dir):
    reports = sorted(Path(report_dir).glob("TEST-*.xml"))
    if not reports:
        raise ValueError(f"Release blocked: no native test reports in {report_dir}")
    cases = [case for report in reports for case in ET.parse(report).getroot().iter("testcase")]
    skipped = sum(case.find("skipped") is not None for case in cases)
    failed = sum(case.find("failure") is not None or case.find("error") is not None for case in cases)
    executed = len(cases) - skipped
    if executed == 0:
        raise ValueError("Release blocked: zero native tests executed")
    if skipped or failed:
        raise ValueError(f"Release blocked: {executed} native tests executed, {skipped} skipped, {failed} failed")
    print(f"Native contract gate: {executed} executed, 0 skipped, 0 failed")
    return executed


def rewrite_links(text, source_document, bundled_document, root, revision):
    def repository_link(match):
        label, target_path = match.groups()
        if ":" in target_path or target_path.startswith("#"):
            return match.group(0)
        local_path, _, anchor = target_path.partition("#")
        decoded_path = unquote(local_path)
        if (bundled_document.parent / decoded_path).exists():
            return match.group(0)
        repository_path = (source_document.parent / decoded_path).resolve().relative_to(root.resolve())
        url = f"https://github.com/alondero/nestlin/blob/{revision}/{quote(repository_path.as_posix())}"
        return f"[{label}]({url}" + (f"#{anchor}" if anchor else "") + ")"
    return re.sub(r"\[([^\]]+)\]\(([^)]+)\)", repository_link, text)


def copy_player_materials(root, destination, revision):
    destination.mkdir(parents=True, exist_ok=True)
    sources = {
        "LICENSE": "LICENSE",
        "MAPPER_SUPPORT.md": "MAPPER_SUPPORT.md",
        "native/rcheevos/LICENSE": "RCHEEVOS-LICENSE",
        "native/rcheevos/NOTICE": "RCHEEVOS-NOTICE",
        **{f"docs/{name}.md": f"docs/{name}.md" for name in ("USER_GUIDE", "TROUBLESHOOTING", "CHEATS")},
    }
    for source_path, target_path in sources.items():
        source = root / source_path
        target = destination / target_path
        target.parent.mkdir(parents=True, exist_ok=True)
        # Every listed file is required; missing legal material must fail packaging.
        shutil.copy2(source, target)
    # Resolve offline links only after every bundled document is present.
    for source_path, target_path in sources.items():
        source = root / source_path
        target = destination / target_path
        if target.suffix == ".md":
            text = rewrite_links(target.read_text(encoding="utf-8"), source, target, root, revision)
            target.write_text(text, encoding="utf-8")


def package(tag, target):
    validate(tag)
    verify_native_tests(ROOT / "build/test-results/testNativeRa")
    system = platform.system()
    arch = {"AMD64": "x86_64", "arm64": "aarch64"}.get(platform.machine(), platform.machine())
    host = {"Windows": "windows", "Linux": "linux", "Darwin": "macos"}[system]
    if target != f"{host}-{arch}":
        raise ValueError(f"Target {target} does not match host {host}-{arch}")
    work = ROOT / "build" / "release"
    if work.exists():
        raise ValueError("build/release already exists; run Gradle clean before packaging")
    inputs = work / "input"
    inputs.mkdir(parents=True)
    shutil.copy2(ROOT / "build/libs/nestlin-all.jar", inputs)
    jdk = Path(os.environ["JAVA_HOME"])
    suffix = ".exe" if system == "Windows" else ""
    runtime = work / "runtime"
    run(jdk / "bin" / f"jlink{suffix}", "--add-modules", "ALL-MODULE-PATH",
        "--output", runtime)
    image_dir = work / "image"
    run(jdk / "bin" / f"jpackage{suffix}", "--type", "app-image", "--name", "Nestlin",
        "--input", inputs, "--main-jar", "nestlin-all.jar", "--main-class",
        "com.github.alondero.nestlin.ui.ApplicationKt", "--app-version", tag[1:],
        "--runtime-image", runtime, "--dest", image_dir,
        *(["--win-console"] if system == "Windows" else []))
    image = image_dir / ("Nestlin.app" if system == "Darwin" else "Nestlin")
    launcher = image / {"Windows": "Nestlin.exe", "Linux": "bin/Nestlin",
                        "Darwin": "Contents/MacOS/Nestlin"}[system]
    # Test the actual native launcher and bundled runtime, with no host Java dependency.
    run(launcher, "nra-smoke")
    # Reuse the tracked NROM header/layout; replace its CPU-test entry with a tiny
    # rendering program. This generated fixture is only a packaging smoke, not a game.
    fixture = bytearray((ROOT / "src/test/resources/nestest.nes").read_bytes())
    program = bytes.fromhex("78 A9 3F 8D 06 20 A9 00 8D 06 20 A9 0F 8D 07 20 A9 30 8D 07 20 A9 00 8D 05 20 8D 05 20 A9 0A 8D 01 20 4C 22 80")
    fixture[16:16 + len(program)] = program
    prg_size = fixture[4] * 16384
    fixture[16 + prg_size - 4:16 + prg_size - 2] = bytes.fromhex("00 80")
    # Identical 0xAA rows in tile zero create one-pixel vertical stripes.
    fixture[16 + prg_size:16 + prg_size + 16] = bytes([0xAA] * 8 + [0] * 8)
    smoke_rom = work / "render-smoke.nes"
    smoke_rom.write_bytes(fixture)
    result = subprocess.run([str(launcher), "bootcheck", str(smoke_rom), "--frames", "120"],
                            cwd=ROOT, check=True, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=180)
    print(result.stdout)
    ratio = re.search(r"maxNonBlankRatio\s+: ([0-9.]+)", result.stdout)
    if not ratio or float(ratio.group(1)) < 0.4:
        raise ValueError("Packaged launcher did not render the vertical-stripe fixture")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    materials = image / "Contents/Resources" if system == "Darwin" else image
    copy_player_materials(ROOT, materials, revision)
    if system == "Darwin":
        library = ROOT / "build/native-ra/macos/librcheevos_facade.dylib"
        run("lipo", "-verify_arch", "x86_64", "arm64", library)
        run("lipo", "-archs", library)
    assets = work / "assets"
    assets.mkdir()
    basename = f"nestlin-{tag}-{target}"
    if system == "Windows":
        shutil.make_archive(str(assets / basename), "zip", image_dir)
    else:
        with tarfile.open(assets / f"{basename}.tar.gz", "w:gz") as archive:
            archive.add(image, arcname=image.name)
    shutil.copy2(inputs / "nestlin-all.jar", assets / f"{basename}.jar")
    # Preserve the stable native asset names used by Gradle fetchNativeRa.
    native = ROOT / "build/native-ra" / host
    native_id = "macos-universal" if host == "macos" else target
    # Only one macOS runner emits the compatibility asset (the fragment records host bytes).
    if target != "macos-aarch64":
        libs = list(native.glob("*.dll")) + list(native.glob("*.so")) + list(native.glob("*.dylib"))
        if len(libs) != 1:
            raise ValueError("Exactly one native RA library is required")
        with zipfile.ZipFile(assets / f"rcheevos_facade-{native_id}.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            for source in [libs[0], native / "MANIFEST.fragment.json"]:
                archive.write(source, source.name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    notes_parser = commands.add_parser("validate", help="Check notes against the tagged source save versions")
    notes_parser.add_argument("tag")
    package_parser = commands.add_parser("package", help="Build and test the runtime-bundled application")
    package_parser.add_argument("tag")
    package_parser.add_argument("platform")
    native_parser = commands.add_parser("native-tests", help="Require executed native tests with no skips or failures")
    native_parser.add_argument("report_dir", nargs="?", default=ROOT / "build/test-results/testNativeRa")
    args = parser.parse_args()
    if args.command == "validate":
        validate(args.tag)
    elif args.command == "native-tests":
        verify_native_tests(args.report_dir)
    else:
        package(args.tag, args.platform)
