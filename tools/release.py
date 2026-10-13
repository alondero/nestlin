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
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


def validate(tag):
    if not re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag):
        raise ValueError("Use a stable semantic version tag: vX.Y.Z")
    notes = ROOT / "docs" / "releases" / f"{tag}.md"
    text = notes.read_text(encoding="utf-8")
    if text.splitlines()[0] != f"# Nestlin {tag}":
        raise ValueError(f"{notes}: title must match tag")
    for heading in ("Highlights", "Downloads", "Known limitations", "Save compatibility"):
        if f"## {heading}" not in text:
            raise ValueError(f"{notes}: missing {heading}")
    if re.search(r"\b(TODO|TBD)\b", text):
        raise ValueError("Complete release notes before tagging")


def package(tag, target):
    validate(tag)
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
    # Alternating pattern rows in tile zero create visible output without game assets.
    fixture[16 + prg_size:16 + prg_size + 16] = bytes([0xAA] * 8 + [0] * 8)
    smoke_rom = work / "render-smoke.nes"
    smoke_rom.write_bytes(fixture)
    result = subprocess.run([str(launcher), "bootcheck", str(smoke_rom), "--frames", "120"],
                            cwd=ROOT, check=True, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=180)
    print(result.stdout)
    ratio = re.search(r"maxNonBlankRatio\s+: ([0-9.]+)", result.stdout)
    if not ratio or float(ratio.group(1)) < 0.4:
        raise ValueError("Packaged launcher did not render the checkerboard fixture")
    for name in ("LICENSE", "MAPPER_SUPPORT.md"):
        source = ROOT / name
        if source.exists():
            shutil.copy2(source, image)
    shutil.copytree(ROOT / "docs", image / "docs")
    shutil.copy2(ROOT / "native/rcheevos/LICENSE", image / "RCHEEVOS-LICENSE")
    shutil.copy2(ROOT / "native/rcheevos/NOTICE", image / "RCHEEVOS-NOTICE")
    # Keep bundled documentation readable; repository-only links open the tagged source.
    for document in image.rglob("*.md"):
        def repository_link(match):
            label, target_path = match.groups()
            if ":" in target_path or target_path.startswith("#"):
                return match.group(0)
            local_path, _, anchor = target_path.partition("#")
            if (document.parent / local_path).exists():
                return match.group(0)
            source_document = ROOT / document.relative_to(image)
            repository_path = (source_document.parent / local_path).resolve().relative_to(ROOT)
            url = f"https://github.com/alondero/nestlin/blob/{tag}/{quote(repository_path.as_posix())}"
            return f"[{label}]({url}" + (f"#{anchor}" if anchor else "") + ")"
        text = document.read_text(encoding="utf-8")
        document.write_text(re.sub(r"\[([^\]]+)\]\(([^)]+)\)", repository_link, text), encoding="utf-8")
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
    parser.add_argument("command", choices=["validate", "package"])
    parser.add_argument("tag")
    parser.add_argument("platform", nargs="?")
    args = parser.parse_args()
    if args.command == "validate":
        validate(args.tag)
    elif args.platform:
        package(args.tag, args.platform)
    else:
        parser.error("package requires a platform")
