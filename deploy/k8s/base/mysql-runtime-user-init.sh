#!/bin/sh
set -eu

case "${BATON_GO_DB_USERNAME}" in
  ''|*[!a-z0-9_]*)
    echo >&2 'runtime database username must contain only lowercase letters, digits, and underscore'
    exit 1
    ;;
esac

case "${BATON_GO_DB_PASSWORD}" in
  ''|*[!A-Za-z0-9_-]*)
    echo >&2 'runtime database password must use an unpadded base64url or hex alphabet'
    exit 1
    ;;
esac

if [ "${#BATON_GO_DB_PASSWORD}" -lt 32 ]; then
  echo >&2 'runtime database password must contain at least 32 characters'
  exit 1
fi

MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql --protocol=SOCKET --user=root <<EOSQL
CREATE USER '${BATON_GO_DB_USERNAME}'@'%' IDENTIFIED BY '${BATON_GO_DB_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE ON baton_go.* TO '${BATON_GO_DB_USERNAME}'@'%';
EOSQL
