#!/usr/bin/env bash
set -euo pipefail

readonly EXPECTED_WRAPPER_SHA256="7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"
readonly REVOKED_KEYSTORE_SHA256="c49f0d17b35b8e6620a20eced4b8a14274a3050592543e6a67aa7cc52bca1599"
readonly REVOKED_KEYSTORE_BLOB="91dcf3627d9acf8aebfdfde13f9f3fd78acf87c2"
readonly WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
readonly NATIVE_PATCH="buildScript/lib/core/patches/selector-instance-callback.patch"

die() {
  printf 'Repository integrity check failed: %s\n' "$*" >&2
  exit 1
}

verify_repository() {
  [[ -f "$WRAPPER_JAR" ]] || die "missing $WRAPPER_JAR"

  local wrapper_sha256
  wrapper_sha256=$(sha256sum "$WRAPPER_JAR" | awk '{ print tolower($1) }')
  [[ "$wrapper_sha256" == "$EXPECTED_WRAPPER_SHA256" ]] ||
    die "Gradle wrapper JAR SHA-256 is $wrapper_sha256; expected $EXPECTED_WRAPPER_SHA256"

  local ignored_path
  for ignored_path in release.keystore release.keystore.local; do
    git check-ignore -q -- "$ignored_path" || die "$ignored_path must remain ignored"
  done

  local -a tracked_signing_files=()
  mapfile -t tracked_signing_files < <(
    git ls-files | grep -Ei '(^|/)(release\.keystore(\.local)?|[^/]+\.(jks|keystore|p12|pfx))$' || true
  )
  if (( ${#tracked_signing_files[@]} > 0 )); then
    printf 'Tracked signing material:\n%s\n' "${tracked_signing_files[*]}" >&2
    die "private signing material must stay outside Git"
  fi

  local compromised_paths
  compromised_paths=$(
    git ls-files --stage |
      awk -v revoked="$REVOKED_KEYSTORE_BLOB" '$2 == revoked { print $4 }'
  )
  [[ -z "$compromised_paths" ]] ||
    die "the revoked release keystore blob is tracked at: $compromised_paths"

  [[ -f "$NATIVE_PATCH" ]] || die "missing native patch input: $NATIVE_PATCH"

  local workflow
  for workflow in .github/workflows/ci.yml .github/workflows/preview.yml .github/workflows/release.yml; do
    awk '
      /hashFiles\(/ { in_hash_files = 1 }
      in_hash_files && index($0, "\047buildScript/lib/core/**/*\047") { found = 1 }
      in_hash_files && /\)[[:space:]]*}}/ { in_hash_files = 0 }
      END { exit(found ? 0 : 1) }
    ' "$workflow" ||
      die "$workflow native cache key must include every core build input"
  done
}

verify_keystore() {
  local keystore_path=$1
  [[ -s "$keystore_path" ]] || die "keystore is missing or empty: $keystore_path"

  local keystore_sha256
  keystore_sha256=$(sha256sum "$keystore_path" | awk '{ print tolower($1) }')
  [[ "$keystore_sha256" != "$REVOKED_KEYSTORE_SHA256" ]] ||
    die "the decoded keystore matches the revoked repository credential"
}

verify_archives() {
  local archive_root=$1
  [[ -d "$archive_root" ]] || die "archive directory is missing: $archive_root"

  local archive archive_entries
  local archive_count=0
  while IFS= read -r -d '' archive; do
    archive_count=$((archive_count + 1))
    archive_entries=$(unzip -Z1 "$archive") || die "could not inspect archive: $archive"
    if grep -Eiq '(^|/)[^/]+\.(jks|keystore|p12|pfx)$' <<< "$archive_entries"; then
      die "signing material is packaged in: $archive"
    fi
  done < <(find "$archive_root" -type f \( -name '*.apk' -o -name '*.aab' -o -name '*.aar' -o -name '*.zip' \) -print0)

  (( archive_count > 0 )) || die "no build archives found under: $archive_root"
}

verify_repository

while (( $# > 0 )); do
  case "$1" in
    --keystore)
      (( $# >= 2 )) || die "--keystore requires a path"
      verify_keystore "$2"
      shift 2
      ;;
    --archives)
      (( $# >= 2 )) || die "--archives requires a directory"
      verify_archives "$2"
      shift 2
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

printf 'Repository integrity checks passed.\n'
