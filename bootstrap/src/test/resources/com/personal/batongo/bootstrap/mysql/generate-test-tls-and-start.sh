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
subjectAltName=DNS:baton-go-mysql
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

rm -f \
  "${tls_dir}/ca.key" \
  "${tls_dir}/ca.srl" \
  "${tls_dir}/tls.csr" \
  "${tls_dir}/server.ext"
chown mysql:mysql \
  "${tls_dir}/ca.pem" \
  "${tls_dir}/tls.crt" \
  "${tls_dir}/tls.key"
chmod 0440 \
  "${tls_dir}/ca.pem" \
  "${tls_dir}/tls.crt" \
  "${tls_dir}/tls.key"

exec /usr/local/bin/docker-entrypoint.sh "$@"
