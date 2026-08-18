#!/usr/bin/env bash
set -euo pipefail

ARCHIVE=${1:?"usage: check_elf_alignment.sh <apk-or-aar>"}
NDK_VERSION=${NDK_VERSION:-28.2.13676358}
NDK_ROOT=${ANDROID_NDK_HOME:-"${ANDROID_HOME:?ANDROID_HOME is required}/ndk/$NDK_VERSION"}

case "$(uname -s)" in
  Linux*) HOST_TAG=linux-x86_64 ;;
  Darwin*) HOST_TAG=darwin-x86_64 ;;
  MINGW*|MSYS*|CYGWIN*) HOST_TAG=windows-x86_64 ;;
  *) echo "Unsupported host: $(uname -s)" >&2; exit 2 ;;
esac

READELF="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-readelf"
if [[ $HOST_TAG == windows-* ]]; then
  READELF="$READELF.exe"
fi
[[ -x "$READELF" ]] || { echo "llvm-readelf not found: $READELF" >&2; exit 2; }

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT
unzip -q "$ARCHIVE" -d "$TMP_DIR"

mapfile -d '' LIBRARIES < <(find "$TMP_DIR" -type f -name '*.so' -print0)
(( ${#LIBRARIES[@]} > 0 )) || { echo "No native libraries found in $ARCHIVE" >&2; exit 2; }

for library in "${LIBRARIES[@]}"; do
  while read -r alignment; do
    if (( alignment < 0x4000 )); then
      echo "UNALIGNED: ${library#"$TMP_DIR"/} LOAD alignment=$alignment" >&2
      exit 1
    fi
  done < <("$READELF" -lW "$library" | awk '$1 == "LOAD" { print $NF }')
  echo "ALIGNED: ${library#"$TMP_DIR"/}"
done
