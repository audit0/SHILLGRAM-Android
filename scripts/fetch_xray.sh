#!/usr/bin/env bash
# SHILLGRAM: fetches the official Xray-core Android builds for the built-in
# SHILLVPN and places them as TMessagesProj/src/main/jniLibs/<abi>/libxray.so
# (a "lib*.so" name makes Android unpack it, executable, into the app's
# nativeLibraryDir). The binaries are not in git: CI and local builds run
# this script before Gradle.
#
# Every archive is checked twice: against the .dgst file of the same
# release and against the SHA-256 pinned below, so a replaced release asset
# does not slip into the app. Updating Xray = change VERSION and the pins.
set -euo pipefail

VERSION="v26.9.9"
BASE="https://github.com/XTLS/Xray-core/releases/download/${VERSION}"

# abi|asset|sha256 of the zip
ASSETS=(
  "arm64-v8a|Xray-android-arm64-v8a.zip|f18625edf2360df8f857d8a2d947f69dd137b31849e7b9b530200d7e5f482766"
  "x86_64|Xray-android-amd64.zip|7317be77220ae9fba70692bccb079dc91e405a42b77d904c324d54820d815532"
)
# armeabi-v7a and x86 have no official Android build in this release: on
# those devices the app says the VPN core is missing.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${ROOT}/TMessagesProj/src/main/jniLibs"
STAMPS="${ROOT}/build/xray"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

for item in "${ASSETS[@]}"; do
  IFS='|' read -r abi asset pinned <<<"${item}"
  target="${OUT}/${abi}/libxray.so"
  stamp="${STAMPS}/${abi}-${VERSION}-${pinned}"
  if [[ -f "${target}" && -f "${stamp}" ]]; then
    echo "xray ${VERSION} ${abi}: up to date"
    continue
  fi
  echo "xray ${VERSION} ${abi}: downloading ${asset}"
  curl -fsSL --retry 3 -o "${WORK}/${asset}" "${BASE}/${asset}"
  curl -fsSL --retry 3 -o "${WORK}/${asset}.dgst" "${BASE}/${asset}.dgst"
  actual="$(sha256 "${WORK}/${asset}")"
  published="$(awk -F'= ' '/^SHA2-256=/{print $2}' "${WORK}/${asset}.dgst" | tr -d '[:space:]')"
  if [[ "${actual}" != "${published}" ]]; then
    echo "xray ${abi}: sha256 does not match the release .dgst" >&2
    exit 1
  fi
  if [[ "${actual}" != "${pinned}" ]]; then
    echo "xray ${abi}: sha256 does not match the pinned value" >&2
    exit 1
  fi
  rm -rf "${WORK}/unpacked" && mkdir -p "${WORK}/unpacked"
  unzip -q -o "${WORK}/${asset}" xray -d "${WORK}/unpacked"
  mkdir -p "${OUT}/${abi}"
  install -m 0755 "${WORK}/unpacked/xray" "${target}"
  mkdir -p "${STAMPS}"
  rm -f "${STAMPS}/${abi}-"*
  touch "${stamp}"
  echo "xray ${VERSION} ${abi}: ok"
done
