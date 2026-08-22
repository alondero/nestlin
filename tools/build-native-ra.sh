#!/usr/bin/env bash
# Builds the native RetroAchievements shared library on Linux/macOS.
# Cross-platform sibling of tools/build-native-ra.ps1 (which targets
# Windows + PowerShell). The Gradle :buildNative task picks the right
# script based on the host OS.
#
# Usage:
#   build-native-ra.sh -o <output-dir> -r <rcheevos-dir> -f <façade-dir>
#
# Exit codes:
#   0   built successfully
#   1   compiler or build failure
#   2   no supported compiler found (Gradle treats this as "build skipped")

set -u

usage() {
    echo "Usage: $0 -o <output-dir> -r <rcheevos-dir> -f <façade-dir>" >&2
    exit 2
}

OUT_DIR=""
RCH_DIR=""
FACADE_DIR=""
COMPILER="auto"

while getopts "o:r:f:c:" opt; do
    case $opt in
        o) OUT_DIR="$OPTARG" ;;
        r) RCH_DIR="$OPTARG" ;;
        f) FACADE_DIR="$OPTARG" ;;
        c) COMPILER="$OPTARG" ;;
        *) usage ;;
    esac
done

if [ -z "$OUT_DIR" ] || [ -z "$RCH_DIR" ] || [ -z "$FACADE_DIR" ]; then
    usage
fi

# Resolve compiler
GCC_BIN=""
CLANG_BIN=""
if [ "$COMPILER" = "auto" ] || [ "$COMPILER" = "gcc" ]; then
    GCC_BIN="$(command -v gcc 2>/dev/null || true)"
fi
if [ -z "$GCC_BIN" ] && { [ "$COMPILER" = "auto" ] || [ "$COMPILER" = "clang" ]; }; then
    CLANG_BIN="$(command -v clang 2>/dev/null || true)"
fi

if [ -n "$GCC_BIN" ]; then
    CC="$GCC_BIN"
elif [ -n "$CLANG_BIN" ]; then
    CC="$CLANG_BIN"
else
    echo "[BUILD-NATIVE-RA] No supported C compiler found (tried gcc, clang). Native RA build skipped." >&2
    exit 2
fi

echo "[BUILD-NATIVE-RA] Compiler: $CC"
echo "[BUILD-NATIVE-RA] Output:   $OUT_DIR"

mkdir -p "$OUT_DIR"
mkdir -p "$OUT_DIR/obj"

# rcheevos sources (relative to rcheevos/src)
RCH_SOURCES=(
    "rapi/rc_api_common.c" "rapi/rc_api_editor.c" "rapi/rc_api_info.c"
    "rapi/rc_api_runtime.c" "rapi/rc_api_user.c"
    "rc_client.c" "rc_client_external.c" "rc_compat.c" "rc_util.c" "rc_version.c"
    "rcheevos/alloc.c" "rcheevos/condition.c" "rcheevos/condset.c"
    "rcheevos/consoleinfo.c" "rcheevos/format.c" "rcheevos/lboard.c"
    "rcheevos/memref.c" "rcheevos/operand.c" "rcheevos/rc_validate.c"
    "rcheevos/richpresence.c" "rcheevos/runtime.c"
    "rcheevos/runtime_progress.c" "rcheevos/trigger.c" "rcheevos/value.c"
    "rhash/aes.c" "rhash/cdreader.c" "rhash/hash.c" "rhash/hash_disc.c"
    "rhash/hash_encrypted.c" "rhash/hash_rom.c" "rhash/hash_zip.c"
    "rhash/md5.c"
)

RCH_SRC_DIR="$RCH_DIR/src"
RCH_INCLUDE_DIR="$RCH_DIR/include"

# Detect host OS for output naming
case "$(uname -s)" in
    Darwin*)                HOST="macos" ;;
    Linux*)                 HOST="linux" ;;
    MINGW*|MSYS*|CYGWIN*)   HOST="windows" ;;
    *)                      HOST="unknown" ;;
esac

case "$HOST" in
    macos)   LIB_NAME="librcheevos_facade.dylib" LINK_EXT="-fPIC -dynamiclib" LINK_LIBS="" ;;
    linux)   LIB_NAME="librcheevos_facade.so"    LINK_EXT="-fPIC"             LINK_LIBS="-lpthread -ldl" ;;
    windows) LIB_NAME="rcheevos_facade.dll"      LINK_EXT=""                   LINK_LIBS="-lws2_32" ;;
    *) echo "Unsupported host: $HOST" >&2; exit 1 ;;
esac

# Compile each rcheevos source (caching by mtime)
OBJ_FILES=()
for src in "${RCH_SOURCES[@]}"; do
    obj_name="$(echo "$src" | tr '/' '_' | sed 's/\.c$/.o/')"
    obj_path="$OUT_DIR/obj/$obj_name"
    OBJ_FILES+=("$obj_path")
    if [ -f "$obj_path" ] && [ "$obj_path" -nt "$RCH_SRC_DIR/$src" ]; then
        echo "[BUILD-NATIVE-RA] cached: $src"
        continue
    fi
    echo "[BUILD-NATIVE-RA] cc: $src"
    "$CC" -c -std=c99 -O2 -fvisibility=hidden -fPIC -DRC_CLIENT_SUPPORTS_HASH=1 \
        -I"$RCH_INCLUDE_DIR" \
        -I"$RCH_SRC_DIR" \
        -I"$RCH_SRC_DIR/rcheevos" \
        -I"$RCH_SRC_DIR/rhash" \
        -I"$RCH_SRC_DIR/rapi" \
        "$RCH_SRC_DIR/$src" \
        -o "$obj_path"
    rc=$?
    if [ $rc -ne 0 ] || [ ! -f "$obj_path" ]; then
        echo "[BUILD-NATIVE-RA] FAIL: $src (cc exit=$rc)" >&2
        exit 1
    fi
done

# Compile the façade
FAC_OBJ="$OUT_DIR/obj/ra_facade.o"
OBJ_FILES+=("$FAC_OBJ")
if [ ! -f "$FAC_OBJ" ] || [ "$FAC_OBJ" -lt "$FACADE_DIR/ra_facade.c" ]; then
    echo "[BUILD-NATIVE-RA] cc: ra_facade.c"
    "$CC" -c -std=c99 -O2 -fvisibility=default -fPIC -DRA_FACADE_BUILDING=1 -DRC_CLIENT_SUPPORTS_HASH=1 \
        -I"$FACADE_DIR" \
        -I"$RCH_INCLUDE_DIR" \
        -I"$RCH_SRC_DIR" \
        "$FACADE_DIR/ra_facade.c" \
        -o "$FAC_OBJ"
    rc=$?
    if [ $rc -ne 0 ] || [ ! -f "$FAC_OBJ" ]; then
        echo "[BUILD-NATIVE-RA] FAIL: ra_facade.c (cc exit=$rc)" >&2
        exit 1
    fi
fi

# Link into the shared library
LIB_PATH="$OUT_DIR/$LIB_NAME"
echo "[BUILD-NATIVE-RA] link: $LIB_PATH"
"$CC" -shared $LINK_EXT -o "$LIB_PATH" "${OBJ_FILES[@]}" $LINK_LIBS
rc=$?
if [ $rc -ne 0 ] || [ ! -f "$LIB_PATH" ]; then
    echo "[BUILD-NATIVE-RA] LINK FAILED (cc exit=$rc)" >&2
    exit 1
fi

LIB_SIZE=$(stat -c %s "$LIB_PATH" 2>/dev/null || stat -f %z "$LIB_PATH" 2>/dev/null || echo "?")
echo "[BUILD-NATIVE-RA] Built: $LIB_PATH ($LIB_SIZE bytes)"

# Emit a per-platform manifest fragment (issue #273 AC: runtime validates
# checksum + pinned rcheevos version). The Gradle :buildNative task picks
# up this file alongside the .so / .dylib and merges the per-platform
# fragments into a single MANIFEST.json that ships in the runnable JAR.
case "$HOST" in
    macos)   PLATFORM_ID="macos-universal";   RESOURCE_DIR="macos" ;;
    linux)   PLATFORM_ID="linux-x86_64";       RESOURCE_DIR="linux" ;;
    windows) PLATFORM_ID="windows-x86_64";     RESOURCE_DIR="windows" ;;
    *)       echo "Unsupported host: $HOST" >&2; exit 1 ;;
esac
SHA256=$(sha256sum "$LIB_PATH" 2>/dev/null | awk '{print $1}')
MANIFEST_PATH="$OUT_DIR/MANIFEST.fragment.json"
cat > "$MANIFEST_PATH" <<EOF
{
  "platforms": [
    {
      "platformId": "$PLATFORM_ID",
      "libraryFilename": "$LIB_NAME",
      "resourcePath": "native-ra/$RESOURCE_DIR/$LIB_NAME",
      "sha256Hex": "$SHA256",
      "sizeBytes": $LIB_SIZE
    }
  ]
}
EOF
echo "[BUILD-NATIVE-RA] Wrote manifest fragment: $MANIFEST_PATH"

exit 0