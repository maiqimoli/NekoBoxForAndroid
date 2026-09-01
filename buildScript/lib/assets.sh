#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
readonly ASSET_PARENT="$REPO_ROOT/app/src/main/assets"
readonly ASSET_DIR="$ASSET_PARENT/sing-box"
readonly LOCK_DIR="$REPO_ROOT/app/src/main/.sing-box.install.lock"
readonly GEOIP_VERSION="20260812"
readonly GEOIP_SHA256="d8f4d22abee199b73c019df267e8dc649e868da3b130753640aa9b05d11040c0"
readonly GEOSITE_VERSION="20260830143421"
readonly GEOSITE_SHA256="23ad14b560ca3b68e56f04bdaaaee8ca1ee7d89c81f37bfa957d1ddd3eab5df8"

mkdir -p "$ASSET_PARENT"
staging_dir=""
backup_dir=""
lock_owned=false
cleanup() {
  local status=$?

  if [[ -n "$backup_dir" && -e "$backup_dir" ]]; then
    if [[ ! -e "$ASSET_DIR" ]]; then
      mv -- "$backup_dir" "$ASSET_DIR" || true
    else
      rm -rf -- "$backup_dir" || true
    fi
  fi
  if [[ -n "$staging_dir" && -e "$staging_dir" ]]; then
    rm -rf -- "$staging_dir" || true
  fi
  if [[ "$lock_owned" == true ]]; then
    rmdir -- "$LOCK_DIR" || true
  fi

  return "$status"
}
trap cleanup EXIT

if ! mkdir -- "$LOCK_DIR"; then
  echo "Another asset installation is already running: $LOCK_DIR" >&2
  exit 1
fi
lock_owned=true
staging_dir=$(mktemp -d "$ASSET_PARENT/.sing-box.new.XXXXXX")

verify_sha256() {
  local file=$1
  local expected_sha256=$2
  local actual_sha256

  if command -v sha256sum >/dev/null 2>&1; then
    actual_sha256=$(sha256sum "$file")
  elif command -v shasum >/dev/null 2>&1; then
    actual_sha256=$(shasum -a 256 "$file")
  else
    echo "A SHA-256 tool (sha256sum or shasum) is required" >&2
    return 1
  fi
  actual_sha256=${actual_sha256%% *}

  if [[ "$actual_sha256" != "$expected_sha256" ]]; then
    echo "SHA-256 mismatch for $file" >&2
    echo "Expected: $expected_sha256" >&2
    echo "Actual:   $actual_sha256" >&2
    return 1
  fi
  echo "$file: OK"
}

download_and_verify() {
  local repository=$1
  local version=$2
  local filename=$3
  local expected_sha256=$4
  local output="$staging_dir/$filename"
  local url="https://github.com/${repository}/releases/download/${version}/${filename}"

  echo "Downloading ${repository} ${version}/${filename}"
  curl \
    --proto '=https' \
    --tlsv1.2 \
    --fail \
    --location \
    --silent \
    --show-error \
    --retry 3 \
    --output "$output" \
    "$url"

  verify_sha256 "$output" "$expected_sha256"
}

download_and_verify "SagerNet/sing-geoip" "$GEOIP_VERSION" "geoip.db" "$GEOIP_SHA256"
download_and_verify "SagerNet/sing-geosite" "$GEOSITE_VERSION" "geosite.db" "$GEOSITE_SHA256"

printf '%s' "$GEOIP_VERSION" > "$staging_dir/geoip.version.txt"
printf '%s' "$GEOSITE_VERSION" > "$staging_dir/geosite.version.txt"

# A single compression thread keeps the generated archives stable across machines.
xz -9 --threads=1 "$staging_dir/geoip.db"
xz -9 --threads=1 "$staging_dir/geosite.db"

if [[ -e "$ASSET_DIR" ]]; then
  backup_dir=$(mktemp -d "$ASSET_PARENT/.sing-box.backup.XXXXXX")
  rmdir -- "$backup_dir"
  mv -- "$ASSET_DIR" "$backup_dir"
fi

mv -- "$staging_dir" "$ASSET_DIR"
staging_dir=""

if [[ -n "$backup_dir" ]]; then
  rm -rf -- "$backup_dir"
  backup_dir=""
fi

echo "Installed pinned sing-box assets in $ASSET_DIR"
