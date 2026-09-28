#!/usr/bin/env bash
# SHILLGRAM: checks the SHILLVPN subscription parser and Xray config builder
# on a plain JVM with synthetic links. Optional: XRAY=/path/to/desktop/xray
# also lets Xray itself test the generated config (xray run -test).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${ROOT}/TMessagesProj/src/main/java/io/github/audit0/shillgram/vpn"
OUT="$(mktemp -d)"
trap 'rm -rf "${OUT}"' EXIT
javac -d "${OUT}" "${SRC}/Json.java" "${SRC}/XrayConfig.java" "${SRC}/TrialWork.java" \
  "${ROOT}/scripts/vpn_selftest/VpnSelfTest.java"
java -cp "${OUT}" VpnSelfTest "${OUT}/config.json"
if [[ -n "${XRAY:-}" ]]; then
  "${XRAY}" run -test -config stdin: < "${OUT}/config.json"
fi
