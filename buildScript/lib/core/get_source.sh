#!/bin/bash
set -e

BASEDIR="$(pwd)"

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/sing-box.git
fi
pushd sing-box
git checkout "$COMMIT_SING_BOX"
git reset --hard "$COMMIT_SING_BOX"
if [ -f "$BASEDIR/buildScript/lib/core/sing-box-x25519mlkem768.patch" ]; then
  if git apply --check "$BASEDIR/buildScript/lib/core/sing-box-x25519mlkem768.patch" 2>/dev/null; then
    echo "Applying sing-box-x25519mlkem768.patch..."
    git apply "$BASEDIR/buildScript/lib/core/sing-box-x25519mlkem768.patch"
  else
    echo "sing-box patch already applied or not applicable, skipping."
  fi
fi
if [ -f "$BASEDIR/buildScript/lib/core/sing-box-1.14-boxapi.patch" ]; then
  echo "Applying sing-box-1.14-boxapi.patch..."
  git apply "$BASEDIR/buildScript/lib/core/sing-box-1.14-boxapi.patch"
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
