#!/bin/sh
set -eu

umask 077
LC_ALL=C
export LC_ALL

TLS_DIRECTORY=${1:-/etc/mysql/tls}
RUNTIME_INIT_SCRIPT=${2:-/opt/baton-go/mysql-init/10-create-runtime-user.sh}
EXPECTED_SERVER_DNS=baton-go-mysql

fail() {
  printf '%s\n' "mysql bootstrap preflight failed: $1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required validation tool is unavailable"
}

require_file_mode() {
  label=$1
  path=$2
  expected_mode=$3

  [ -f "$path" ] || fail "$label is missing"
  [ -s "$path" ] || fail "$label is empty"
  [ -r "$path" ] || fail "$label is not readable by the mysql user"

  actual_mode=$(stat -Lc '%a' -- "$path" 2>/dev/null) \
    || fail "$label permissions cannot be inspected"
  [ "$actual_mode" = "$expected_mode" ] \
    || fail "$label permissions do not match the deployment contract"
}

validate_username() {
  value=$1

  case "$value" in
    ''|*[!a-z0-9_]*)
      fail "database username does not use the approved alphabet"
      ;;
  esac

  [ "${#value}" -le 32 ] || fail "database username exceeds the MySQL account limit"
}

validate_password() {
  value=$1

  case "$value" in
    ''|*[!A-Za-z0-9_-]*)
      fail "database password does not use the approved alphabet"
      ;;
  esac

  [ "${#value}" -ge 32 ] || fail "database password is shorter than the deployment contract"
}

require_command grep
require_command openssl
require_command stat

runtime_username=${BATON_GO_DB_USERNAME:-}
migration_username=${MYSQL_USER:-}
runtime_password=${BATON_GO_DB_PASSWORD:-}
migration_password=${MYSQL_PASSWORD:-}
root_password=${MYSQL_ROOT_PASSWORD:-}

validate_username "$runtime_username"
validate_username "$migration_username"
validate_password "$runtime_password"
validate_password "$migration_password"
validate_password "$root_password"

[ "${MYSQL_DATABASE:-}" = baton_go ] || fail "database name does not match the deployment contract"
[ "$runtime_username" = baton_go ] || fail "runtime database username does not match the deployment contract"
[ "$migration_username" = baton_go_migrator ] \
  || fail "migration database username does not match the deployment contract"
[ "$runtime_username" != "$migration_username" ] \
  || fail "runtime and migration database usernames must be distinct"
[ "$runtime_password" != "$migration_password" ] \
  || fail "runtime and migration database passwords must be distinct"
[ "$runtime_password" != "$root_password" ] \
  || fail "runtime and root database passwords must be distinct"
[ "$migration_password" != "$root_password" ] \
  || fail "migration and root database passwords must be distinct"

ca_file=${TLS_DIRECTORY}/ca.pem
certificate_file=${TLS_DIRECTORY}/tls.crt
private_key_file=${TLS_DIRECTORY}/tls.key

require_file_mode 'mysql CA bundle' "$ca_file" 440
require_file_mode 'mysql server certificate' "$certificate_file" 440
require_file_mode 'mysql server private key' "$private_key_file" 440
require_file_mode 'runtime-user initialization script' "$RUNTIME_INIT_SCRIPT" 555

openssl x509 -in "$certificate_file" -noout >/dev/null 2>&1 \
  || fail "mysql server certificate is not valid PEM"

if grep -Eq -- \
  '-----BEGIN ENCRYPTED PRIVATE KEY-----|^Proc-Type:[[:space:]]*4,ENCRYPTED|^DEK-Info:' \
  "$private_key_file"; then
  fail "mysql server private key must not be encrypted"
fi

openssl pkey -in "$private_key_file" -passin pass: -noout >/dev/null 2>&1 \
  || fail "mysql server private key is invalid or encrypted"

certificate_public_key=$(
  openssl x509 -in "$certificate_file" -pubkey -noout 2>/dev/null
) || fail "mysql server certificate public key cannot be inspected"
private_public_key=$(
  openssl pkey -in "$private_key_file" -passin pass: -pubout 2>/dev/null
) || fail "mysql server private key public key cannot be inspected"
[ -n "$certificate_public_key" ] \
  || fail "mysql server certificate public key is empty"
[ -n "$private_public_key" ] \
  || fail "mysql server private key public key is empty"
[ "$certificate_public_key" = "$private_public_key" ] \
  || fail "mysql server certificate and private key do not match"

certificate_sans=$(openssl x509 -in "$certificate_file" -noout -ext subjectAltName 2>/dev/null) \
  || fail "mysql server certificate has no subject alternative name extension"
printf '%s\n' "$certificate_sans" \
  | grep -Eq "(^|[[:space:],])DNS:${EXPECTED_SERVER_DNS}([[:space:],]|$)" \
  || fail "mysql server certificate does not contain the required DNS SAN"

openssl verify \
  -CAfile "$ca_file" \
  -purpose sslserver \
  -verify_hostname "$EXPECTED_SERVER_DNS" \
  "$certificate_file" >/dev/null 2>&1 \
  || fail "mysql server certificate cannot be verified by the configured CA and DNS identity"

grep -Fqx '#!/bin/sh' "$RUNTIME_INIT_SCRIPT" \
  || fail "runtime-user initialization script has an unexpected interpreter"
grep -Fq 'MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql --protocol=SOCKET --user=root' "$RUNTIME_INIT_SCRIPT" \
  || fail "runtime-user initialization script does not use the local root socket"
grep -Fq "CREATE USER '\${BATON_GO_DB_USERNAME}'@'%' IDENTIFIED BY '\${BATON_GO_DB_PASSWORD}';" "$RUNTIME_INIT_SCRIPT" \
  || fail "runtime-user initialization script does not create the configured account"
grep -Fq "GRANT SELECT, INSERT, UPDATE, DELETE ON baton_go.* TO '\${BATON_GO_DB_USERNAME}'@'%';" "$RUNTIME_INIT_SCRIPT" \
  || fail "runtime-user initialization script does not contain the least-privilege grant"

printf '%s\n' 'mysql bootstrap preflight passed'
