#!/bin/sh
set -eu
# Never print credentials, including on initialization failure.
umask 077
directory=${1:-/run/db-credentials}
secret=$directory/spring.datasource.password
if [ ! -s "$secret" ]; then
    pending=$(mktemp "$directory/.password.XXXXXX")
    trap 'rm -f "$pending"' EXIT HUP INT TERM
    dd if=/dev/urandom bs=32 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n' > "$pending"
    test -s "$pending"
    chmod 0444 "$pending"
    mv "$pending" "$secret"
fi
