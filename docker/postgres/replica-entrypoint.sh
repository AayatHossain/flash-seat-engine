#!/bin/sh
# Runs a read-only copy of the primary. On the first start the data directory is empty, so it is
# cloned from the primary; after that the copy keeps itself up to date from the primary's change log (WAL).
set -e

if [ ! -s "$PGDATA/PG_VERSION" ]; then
    mkdir -p "$PGDATA"
    chown postgres:postgres "$PGDATA"
    chmod 700 "$PGDATA"
    # --write-recovery-conf makes this a standby that follows the primary.
    until gosu postgres pg_basebackup --host=postgres --username=replicator --pgdata="$PGDATA" \
            --wal-method=stream --write-recovery-conf --checkpoint=fast; do
        echo "Primary not ready yet, retrying"
        rm -rf "${PGDATA:?}"/*
        sleep 2
    done
fi

exec docker-entrypoint.sh postgres
