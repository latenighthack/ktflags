#!/usr/bin/env bash
# Installs the local prerequisites ktflags' build cannot resolve from Maven.
#
# protoc itself is NOT installed here — the build resolves it as a pinned Maven artifact
# (com.google.protobuf:protoc). Only ktbuf's Kotlin codegen plugin, a Go binary published to a
# non-public module path, has to exist on PATH.
set -euo pipefail

# Pinned rather than @latest: protoc-gen-kt has no release tags, and a reinstall that silently
# changes generated output is very hard to diagnose. This is the pseudo-version social pins.
PROTOC_GEN_KT_VERSION="${PROTOC_GEN_KT_VERSION:-v0.0.0-20251214023608-0fa742406fbf}"

if ! command -v go >/dev/null 2>&1; then
    echo "error: go is required to install protoc-gen-kt (https://go.dev/dl/)" >&2
    exit 1
fi

echo "installing latenighthack.com/protoc-gen-kt@${PROTOC_GEN_KT_VERSION}"
go install "latenighthack.com/protoc-gen-kt@${PROTOC_GEN_KT_VERSION}"

GOBIN="$(go env GOBIN)"
[ -n "$GOBIN" ] || GOBIN="$(go env GOPATH)/bin"

if ! command -v protoc-gen-kt >/dev/null 2>&1; then
    echo
    echo "protoc-gen-kt installed to ${GOBIN} but that directory is not on PATH. Add:"
    echo "  export PATH=\"${GOBIN}:\$PATH\""
    echo
    echo "(The build also falls back to ~/go/bin/protoc-gen-kt, so this may already work.)"
fi

echo "ok: $(command -v protoc-gen-kt || echo "${GOBIN}/protoc-gen-kt")"
