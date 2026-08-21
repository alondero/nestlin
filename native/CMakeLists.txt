# Top-level CMake project for the native RetroAchievements façade.
#
# Compiles:
#   - rcheevos v12.4.0 (vendored at native/rcheevos/) as a STATIC library
#   - ra_facade.c (this directory) as a SHARED library that exports the
#     flat ABI declared in ra_facade.h
#
# The shared library is what JNA loads at runtime — `librcheevos_facade.so`
# on Linux, `librcheevos_facade.dylib` on macOS, `rcheevos_facade.dll` on
# Windows. JNA's standard name mapping picks the right artifact per
# platform automatically.
#
# Build invocation (called by Gradle's `:buildNative` task; safe to invoke
# directly for debugging):
#
#   cmake -S native -B build/native \
#         -DCMAKE_BUILD_TYPE=Release \
#         -DBUILD_SHARED_LIBS=ON
#   cmake --build build/native
#
# The Gradle task wires the same commands through `ExecOperations` so the
# CMake build is reproducible from a fresh checkout.
cmake_minimum_required(VERSION 3.16)
project(nestlin_ra_facade C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN YES)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Opt into rcheevos's built-in hash + identify path. See ra_facade.c for
# the matching #define — both have to agree for the rc_client symbols we
# use to be visible.
add_compile_definitions(RC_CLIENT_SUPPORTS_HASH=1)

# ----------------------------------------------------------------------------
# rcheevos v12.4.0 (vendored)
# ----------------------------------------------------------------------------

set(RCHEEVOS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rcheevos)

# The .c files we need. We exclude the libretro / RAIntegration / .natvis
# surfaces (see native/rcheevos/NOTICE for the rationale).
set(RCHEEVOS_SOURCES
    ${RCHEEVOS_DIR}/src/rapi/rc_api_common.c
    ${RCHEEVOS_DIR}/src/rapi/rc_api_editor.c
    ${RCHEEVOS_DIR}/src/rapi/rc_api_info.c
    ${RCHEEVOS_DIR}/src/rapi/rc_api_runtime.c
    ${RCHEEVOS_DIR}/src/rapi/rc_api_user.c
    ${RCHEEVOS_DIR}/src/rc_client.c
    ${RCHEEVOS_DIR}/src/rc_client_external.c
    ${RCHEEVOS_DIR}/src/rc_compat.c
    ${RCHEEVOS_DIR}/src/rc_util.c
    ${RCHEEVOS_DIR}/src/rc_version.c
    ${RCHEEVOS_DIR}/src/rcheevos/alloc.c
    ${RCHEEVOS_DIR}/src/rcheevos/condition.c
    ${RCHEEVOS_DIR}/src/rcheevos/condset.c
    ${RCHEEVOS_DIR}/src/rcheevos/consoleinfo.c
    ${RCHEEVOS_DIR}/src/rcheevos/format.c
    ${RCHEEVOS_DIR}/src/rcheevos/lboard.c
    ${RCHEEVOS_DIR}/src/rcheevos/memref.c
    ${RCHEEVOS_DIR}/src/rcheevos/operand.c
    ${RCHEEVOS_DIR}/src/rcheevos/rc_validate.c
    ${RCHEEVOS_DIR}/src/rcheevos/richpresence.c
    ${RCHEEVOS_DIR}/src/rcheevos/runtime.c
    ${RCHEEVOS_DIR}/src/rcheevos/runtime_progress.c
    ${RCHEEVOS_DIR}/src/rcheevos/trigger.c
    ${RCHEEVOS_DIR}/src/rcheevos/value.c
    ${RCHEEVOS_DIR}/src/rhash/aes.c
    ${RCHEEVOS_DIR}/src/rhash/cdreader.c
    ${RCHEEVOS_DIR}/src/rhash/hash.c
    ${RCHEEVOS_DIR}/src/rhash/hash_disc.c
    ${RCHEEVOS_DIR}/src/rhash/hash_encrypted.c
    ${RCHEEVOS_DIR}/src/rhash/hash_rom.c
    ${RCHEEVOS_DIR}/src/rhash/hash_zip.c
    ${RCHEEVOS_DIR}/src/rhash/md5.c
)

add_library(rcheevos STATIC ${RCHEEVOS_SOURCES})

target_include_directories(rcheevos
    PUBLIC
        ${RCHEEVOS_DIR}/include
        ${RCHEEVOS_DIR}/src
        ${RCHEEVOS_DIR}/src/rcheevos
        ${RCHEEVOS_DIR}/src/rhash
        ${RCHEEVOS_DIR}/src/rapi
)

# rcheevos is C99 with a couple of MSVC-only POSIX shims. We don't need
# any platform libs on POSIX. Windows needs ws2_32 for the URL helpers
# exposed by rc_compat (used by rcheevos when it falls back to a builtin
# HTTP client in some configurations; we never trigger that path, but
# linking ws2_32 is harmless and removes a potential link warning).
if(WIN32)
    target_link_libraries(rcheevos PUBLIC ws2_32)
endif()

# Hide every non-API symbol from the rcheevos static lib. The façade is
# the only thing that should call into rcheevos; reducing symbol surface
# makes the shared-library link faster and removes a class of accidental
# coupling.
set_target_properties(rcheevos PROPERTIES
    C_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)

# ----------------------------------------------------------------------------
# The façade — the shared library JNA loads at runtime
# ----------------------------------------------------------------------------

set(RA_FACADE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ra_facade)

# The shared library name. JNA's standard mapping resolves the JNA-side
# Library name "rcheevos_facade" to librcheevos_facade.so / .dylib /
# rcheevos_facade.dll. We pin the OUTPUT_NAME so the artifact is named
# consistently across generators.
add_library(rcheevos_facade SHARED
    ${RA_FACADE_DIR}/ra_facade.c
)

set_target_properties(rcheevos_facade PROPERTIES
    OUTPUT_NAME rcheevos_facade
    C_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN YES
    POSITION_INDEPENDENT_CODE ON
)

target_compile_definitions(rcheevos_facade PRIVATE RA_FACADE_BUILDING=1)

target_include_directories(rcheevos_facade
    PUBLIC
        ${RA_FACADE_DIR}
    PRIVATE
        ${RCHEEVOS_DIR}/include
)

target_link_libraries(rcheevos_facade PRIVATE rcheevos)

# rcheevos's HTTP client (rc_libretro.c / rc_compat.c) calls back through
# the platform socket layer on POSIX. We link dl + pthread for the symbol
# resolution + threading primitives rcheevos uses internally.
if(UNIX AND NOT APPLE)
    target_link_libraries(rcheevos_facade PRIVATE dl pthread)
elseif(APPLE)
    target_link_libraries(rcheevos_facade PRIVATE pthread)
endif()

# Strip in Release so the artifact isn't dragged down by debug info.
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    if(MSVC)
        target_compile_options(rcheevos_facade PRIVATE /O2)
    else()
        target_compile_options(rcheevos_facade PRIVATE -O2)
    endif()
endif()
