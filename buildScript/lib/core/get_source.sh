#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
readonly SING_BOX_PATCH="$SRC_ROOT/buildScript/lib/core/patches/selector-instance-callback.patch"
[ -f "$SING_BOX_PATCH" ] || {
  echo "missing sing-box patch: $SING_BOX_PATCH" >&2
  exit 1
}
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/sing-box.git
fi
pushd sing-box
git checkout "$COMMIT_SING_BOX"
if git apply --reverse --check "$SING_BOX_PATCH" >/dev/null 2>&1; then
  echo "sing-box selector callback patch already applied"
elif git apply --check "$SING_BOX_PATCH"; then
  git apply "$SING_BOX_PATCH"
  echo "applied sing-box selector callback patch"
else
  echo "sing-box selector callback patch does not match $COMMIT_SING_BOX" >&2
  exit 1
fi
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/libneko.git
fi
pushd libneko
git checkout "$COMMIT_LIBNEKO"
popd

####

popd
