#!/bin/bash

chmod -R 777 .build 2>/dev/null
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi

# Install gomobile
GOMOBILE_COMMIT=17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f
GOMOBILE_MARKER="$GOPATH/bin/gomobile-matsuri.version"
INSTALLED_GOMOBILE_COMMIT=$(cat "$GOMOBILE_MARKER" 2>/dev/null || true)
if [ ! -f "$GOPATH/bin/gomobile-matsuri" ] || [ "$INSTALLED_GOMOBILE_COMMIT" != "$GOMOBILE_COMMIT" ]; then
    git clone https://github.com/MatsuriDayo/gomobile.git
    pushd gomobile
    git checkout "$GOMOBILE_COMMIT"
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
    echo "$GOMOBILE_COMMIT" > "$GOMOBILE_MARKER"
fi

GOBIND=gobind-matsuri gomobile-matsuri init
