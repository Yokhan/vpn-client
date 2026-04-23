#!/bin/bash

chmod -R 777 .build 2>/dev/null
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi

GOMOBILE_REPO="${CORE_GOMOBILE_REPO:-MatsuriDayo/gomobile}"
GOMOBILE_REF="${CORE_GOMOBILE_REF:-master2}"
GOMOBILE_GIT_URL="${CORE_GOMOBILE_GIT_URL:-https://github.com/${GOMOBILE_REPO}.git}"

# Install gomobile
if [ ! -f "$GOPATH/bin/gomobile-matsuri" ]; then
    git clone "$GOMOBILE_GIT_URL" gomobile
    pushd gomobile
	git checkout "$GOMOBILE_REF"
    pushd cmd
    pushd gomobile
    go install -v
    popd
    pushd gobind
    go install -v
    popd
    popd
    rm -rf gomobile
    mv "$GOPATH/bin/gomobile" "$GOPATH/bin/gomobile-matsuri"
    mv "$GOPATH/bin/gobind" "$GOPATH/bin/gobind-matsuri"
fi

GOBIND=gobind-matsuri gomobile-matsuri init
