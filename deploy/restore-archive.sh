#!/bin/sh
(
    set -eu
    mongorestore --host 127.0.0.1 --port 27017 --gzip --archive=/backup/avia_archive \
        --nsInclude='avia.*' --nsFrom='avia.*' --nsTo="${MONGO_INITDB_DATABASE}.*" --stopOnError
)
