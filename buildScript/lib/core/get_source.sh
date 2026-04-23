#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"

SING_BOX_REPO="${CORE_SING_BOX_REPO:-MatsuriDayo/sing-box}"
LIBNEKO_REPO="${CORE_LIBNEKO_REPO:-MatsuriDayo/libneko}"
SING_BOX_GIT_URL="${CORE_SING_BOX_GIT_URL:-https://github.com/${SING_BOX_REPO}.git}"
LIBNEKO_GIT_URL="${CORE_LIBNEKO_GIT_URL:-https://github.com/${LIBNEKO_REPO}.git}"

pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout "$SING_BOX_GIT_URL"
fi
pushd sing-box
git checkout "$COMMIT_SING_BOX"
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout "$LIBNEKO_GIT_URL"
fi
pushd libneko
git checkout "$COMMIT_LIBNEKO"
popd

####

popd
