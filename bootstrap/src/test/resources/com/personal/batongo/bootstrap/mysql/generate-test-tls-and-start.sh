#!/bin/sh
set -eu

tls_dir=/etc/mysql/tls
mkdir -p "${tls_dir}"
umask 077

openssl genpkey \
  -algorithm RSA \
  -pkeyopt rsa_keygen_bits:2048 \
  -out "${tls_dir}/ca.key" >/dev/null 2>&1
openssl req \
  -x509 \
  -new \
  -sha256 \
  -days 2 \
  -key "${tls_dir}/ca.key" \
  -subj '/CN=BATON GO MySQL Test CA' \
  -addext 'basicConstraints=critical,CA:TRUE' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -out "${tls_dir}/ca.pem"

openssl genpkey \
  -algorithm RSA \
  -pkeyopt rsa_keygen_bits:2048 \
  -out "${tls_dir}/tls.key" >/dev/null 2>&1
openssl req \
  -new \
  -sha256 \
  -key "${tls_dir}/tls.key" \
  -subj '/CN=baton-go-mysql' \
  -out "${tls_dir}/tls.csr"
cat >"${tls_dir}/server.ext" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:baton-go-mysql,DNS:localhost
EOF
openssl x509 \
  -req \
  -sha256 \
  -days 2 \
  -in "${tls_dir}/tls.csr" \
  -CA "${tls_dir}/ca.pem" \
  -CAkey "${tls_dir}/ca.key" \
  -CAcreateserial \
  -extfile "${tls_dir}/server.ext" \
  -out "${tls_dir}/tls.crt"

openssl genpkey \
  -algorithm RSA \
  -pkeyopt rsa_keygen_bits:2048 \
  -out "${tls_dir}/wrong-ca.key" >/dev/null 2>&1
openssl req \
  -x509 \
  -new \
  -sha256 \
  -days 2 \
  -key "${tls_dir}/wrong-ca.key" \
  -subj '/CN=BATON GO Untrusted Test CA' \
  -addext 'basicConstraints=critical,CA:TRUE' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -out "${tls_dir}/wrong-ca.pem"

rm -f \
  "${tls_dir}/ca.key" \
  "${tls_dir}/ca.srl" \
  "${tls_dir}/tls.csr" \
  "${tls_dir}/server.ext" \
  "${tls_dir}/wrong-ca.key"
chown mysql:mysql \
  "${tls_dir}/ca.pem" \
  "${tls_dir}/tls.crt" \
  "${tls_dir}/tls.key" \
  "${tls_dir}/wrong-ca.pem"
chmod 0440 \
  "${tls_dir}/ca.pem" \
  "${tls_dir}/tls.crt" \
  "${tls_dir}/tls.key" \
  "${tls_dir}/wrong-ca.pem"

encrypted_tls_dir=/tmp/baton-go-encrypted-mysql-tls
mkdir -p "${encrypted_tls_dir}"
cp "${tls_dir}/ca.pem" "${encrypted_tls_dir}/ca.pem"
cp "${tls_dir}/tls.crt" "${encrypted_tls_dir}/tls.crt"
openssl pkcs8 \
  -topk8 \
  -in "${tls_dir}/tls.key" \
  -passout pass: \
  -out "${encrypted_tls_dir}/tls.key"
chown -R mysql:mysql "${encrypted_tls_dir}"
chmod 0440 "${encrypted_tls_dir}"/*

encrypted_preflight_output=/tmp/baton-go-encrypted-key-preflight.out
if gosu mysql /bin/sh \
  /opt/baton-go/mysql-preflight/mysql-init-preflight.sh \
  "${encrypted_tls_dir}" \
  /docker-entrypoint-initdb.d/10-create-runtime-user.sh \
  >"${encrypted_preflight_output}" 2>&1; then
  printf '%s\n' 'encrypted private key unexpectedly passed mysql bootstrap preflight' >&2
  exit 1
fi
grep -Fqx \
  'mysql bootstrap preflight failed: mysql server private key must not be encrypted' \
  "${encrypted_preflight_output}" \
  || {
    printf '%s\n' 'encrypted private key failed with an unexpected preflight result' >&2
    exit 1
  }
rm -rf "${encrypted_tls_dir}" "${encrypted_preflight_output}"
printf '%s\n' 'mysql encrypted private key negative preflight passed'

mismatched_tls_dir=/tmp/baton-go-mismatched-mysql-tls
mkdir -p "${mismatched_tls_dir}"
cp "${tls_dir}/ca.pem" "${mismatched_tls_dir}/ca.pem"
cp "${tls_dir}/tls.crt" "${mismatched_tls_dir}/tls.crt"
openssl genpkey \
  -algorithm RSA \
  -pkeyopt rsa_keygen_bits:2048 \
  -out "${mismatched_tls_dir}/tls.key" >/dev/null 2>&1
chown -R mysql:mysql "${mismatched_tls_dir}"
chmod 0440 "${mismatched_tls_dir}"/*

mismatched_preflight_output=/tmp/baton-go-mismatched-key-preflight.out
if gosu mysql /bin/sh \
  /opt/baton-go/mysql-preflight/mysql-init-preflight.sh \
  "${mismatched_tls_dir}" \
  /docker-entrypoint-initdb.d/10-create-runtime-user.sh \
  >"${mismatched_preflight_output}" 2>&1; then
  printf '%s\n' 'mismatched private key unexpectedly passed mysql bootstrap preflight' >&2
  exit 1
fi
grep -Fqx \
  'mysql bootstrap preflight failed: mysql server certificate and private key do not match' \
  "${mismatched_preflight_output}" \
  || {
    printf '%s\n' 'mismatched private key failed with an unexpected preflight result' >&2
    exit 1
  }
rm -rf "${mismatched_tls_dir}" "${mismatched_preflight_output}"
printf '%s\n' 'mysql mismatched private key negative preflight passed'

gosu mysql /bin/sh \
  /opt/baton-go/mysql-preflight/mysql-init-preflight.sh \
  "${tls_dir}" \
  /docker-entrypoint-initdb.d/10-create-runtime-user.sh

exec /usr/local/bin/docker-entrypoint.sh "$@"
