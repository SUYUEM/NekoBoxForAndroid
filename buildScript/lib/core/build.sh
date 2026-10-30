#!/usr/bin/env bash

source "buildScript/init/env.sh"
export CGO_ENABLED=1
export GO386=softfloat

cd libcore
GOOS=android GOARCH=arm64 go mod tidy -tags='with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' || true
go mod download || true
rel=1 ./build.sh || exit 1
