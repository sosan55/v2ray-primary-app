# app/src/main/cpp/CMakeLists.txt
#
# Builds libhev-socks5-tunnel.so, the native library that
# com.example.service.HevSocks5Tunnel loads via System.loadLibrary("hev-socks5-tunnel").
#
# Strategy (two stages):
#   1. Pull hev-socks5-tunnel's C sources via ExternalProject_Add and build
#      them as a static library using ITS OWN Makefile (not ndk-build, not
#      its bundled Android JNI wrapper — see jni_bridge.c for why).
#   2. Compile our own jni_bridge.c and link it against that static library,
#      producing the final .so with symbol names matching our Kotlin class.
#
# Requires NDK's `make` to be on PATH during the Gradle build (bundled with
# the NDK toolchain on all supported host OSes) and network access on first
# configure (to git-clone the upstream source). If you'd rather pin an exact
# offline copy of the source instead of cloning at build time, replace the
# GIT_REPOSITORY/GIT_TAG below with SOURCE_DIR pointing at a vendored copy.

cmake_minimum_required(VERSION 3.22.1)
project(hev_socks5_tunnel_jni C)

include(ExternalProject)

set(HEV_TUNNEL_SRC_DIR ${CMAKE_CURRENT_BINARY_DIR}/hev-socks5-tunnel-src)

# Pin to a known-good tag rather than a floating branch, so a fresh build
# a year from now doesn't silently pull in unrelated upstream changes.
# Bump deliberately and re-verify src/hev-main.h against jni_bridge.c's
# extern declarations whenever you change this.
set(HEV_TUNNEL_GIT_TAG "2.15.0")

ExternalProject_Add(
    hev_socks5_tunnel_upstream
    GIT_REPOSITORY https://github.com/heiher/hev-socks5-tunnel.git
    GIT_TAG        ${HEV_TUNNEL_GIT_TAG}
    GIT_SUBMODULES_RECURSE ON
    SOURCE_DIR     ${HEV_TUNNEL_SRC_DIR}
    CONFIGURE_COMMAND ""
    # Upstream's own Makefile detects the Android target (from -DANDROID in
    # CFLAGS, added automatically by the NDK's CMake toolchain file) and
    # tries to also compile src/hev-jni.c — THEIR bundled JNI wrapper
    # (Java_TProxyService_StartService, see jni_bridge.c's header comment
    # for why we don't use it). That file expects <jni.h> to be reachable
    # the way `ndk-build`/Android.mk sets it up, which plain `make` here
    # does not replicate, so it fails with "fatal error: 'jni.h' file not
    # found". We don't link against anything in that file, so the simplest
    # fix is to blank it out post-clone rather than fight its include path.
    PATCH_COMMAND bash -c "printf '' > '${HEV_TUNNEL_SRC_DIR}/src/hev-jni.c'"
    BUILD_IN_SOURCE 1
    # -DFD_SET_DEFINED / -DSOCKLEN_T_DEFINED: without these, the bundled
    # third-part/lwip's "unix" port headers (third-part/lwip/src/ports/unix/
    # include/cc.h) try to typedef fd_set/socklen_t themselves, which
    # conflicts with Android's bionic libc definitions ("typedef
    # redefinition with different types"). Upstream's own Android.mk passes
    # these same two flags for exactly this reason — mirroring them here.
    BUILD_COMMAND
        make static
             CC=${CMAKE_C_COMPILER}
             AR=${CMAKE_AR}
             STRIP=${CMAKE_STRIP}
             "CFLAGS=--target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT} ${CMAKE_C_FLAGS} -fPIC -O2 -DNDEBUG -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED"
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS ${HEV_TUNNEL_SRC_DIR}/bin/libhev-socks5-tunnel.a
)

add_library(hev-socks5-tunnel-core STATIC IMPORTED)
set_target_properties(hev-socks5-tunnel-core PROPERTIES
    IMPORTED_LOCATION ${HEV_TUNNEL_SRC_DIR}/bin/libhev-socks5-tunnel.a
)
add_dependencies(hev-socks5-tunnel-core hev_socks5_tunnel_upstream)

# Final artifact: this name (without lib prefix/.so suffix) must match
# System.loadLibrary("hev-socks5-tunnel") in HevSocks5Tunnel.kt exactly.
add_library(hev-socks5-tunnel SHARED
    jni_bridge.c
)

find_library(log-lib log)

target_link_libraries(hev-socks5-tunnel
    hev-socks5-tunnel-core
    ${log-lib}
)
