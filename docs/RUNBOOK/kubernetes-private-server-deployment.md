# Private Kubernetes 배포 runbook

이 문서는 BATON GO application과 GO 전용 MySQL을 private Kubernetes에 처음 배포하고
업데이트·복구하는 절차다. BATON 또는 ROUND의 database, user, volume이나 Secret은 사용하지
않는다. 저장소의 Kustomize overlay는 다음 경계를 만든다.

| 자원 | 역할 | 기본 노출 |
|---|---|---|
| `Deployment/baton-go` | 링크 생성·조회·폐기와 공개 resolver | `ClusterIP:8080` |
| `Job/baton-go-database-migration` | release image의 Flyway migration-only 실행 | 일회성, network endpoint 없음 |
| `StatefulSet/baton-go-mysql` | GO 전용 `baton_go` database | headless `ClusterIP:3306` |
| `PVC/data-baton-go-mysql-0` | GO MySQL data directory | `ReadWriteOnce`, 10Gi |
| `ConfigMap/baton-go-database-identity` | 변경 불가 runtime·migration username | application·Job·MySQL이 참조 |
| `Secret/baton-go-runtime-credentials` | 관리 Bearer token, 링크 HMAC 비밀 | application Pod만 참조 |
| `Secret/baton-go-database-client-config` | 비밀 query가 없는 TLS JDBC URL | application·migration Job만 참조 |
| `Secret/baton-go-database-runtime-credentials` | DML runtime password | application·MySQL만 참조 |
| `Secret/baton-go-database-migration-credentials` | DDL migration password | migration Job·MySQL init만 참조 |
| `Secret/baton-go-database-bootstrap-credentials` | local root password | MySQL Pod만 참조 |
| `Secret/baton-go-mysql-server-tls` | MySQL CA, server certificate와 private key | MySQL Pod만 mount |
| `Secret/baton-go-mysql-client-tls` | 공개 CA만 든 Connector/J PKCS12 truststore | application·migration Job만 mount |

이 배포만으로 public production rollout이 승인되지는 않는다. PRD-0003의 BATON session,
participation grant, room mapping, edge routing과 기존 데이터 inventory gate는 별도다.

## 1. 배포 전 결정

다음을 먼저 확정한다.

1. cluster에 default StorageClass가 있고 `ReadWriteOnce` PVC를 동적 provision할 수 있는지
   확인한다. 특정 `local-path`, CSI 또는 `hostPath`는 저장소 매니페스트에 고정하지 않는다.
2. registry에서 node가 가져올 수 있는 immutable BATON GO release image를 준비한다.
3. public HTTPS origin과 BATON·ROUND의 shared HTTPS origin을 확정한다.
4. 사용하는 CNI가 Kubernetes NetworkPolicy와 node-to-Pod kubelet probe를 어떻게 집행하는지
   확인한다. 지원하지 않으면 application·MySQL 접근 제한을 firewall 또는 CNI 정책으로 별도
   보완한다. host-network ingress controller를 쓰면 source selector 적용 여부도 확인한다.
5. DB backup 대상, 보존 기간, restore 담당자와 HMAC Secret version의 공동 복구 단위를
   확정한다.
6. Kubernetes Secret의 at-rest encryption을 활성화한다. `baton-go` namespace의 Secret
   `get/list/watch`, Pod `exec/attach/ephemeralcontainers`, Pod·Deployment·StatefulSet·Job의
   `create/update/patch`는 직접 또는 workload mount를 통해 Secret을 읽을 수 있는 권한으로
   취급하고 승인된 배포·운영 주체에만 최소 부여한다. Secret object는 base64 encoding만으로
   보호되지 않으며 cluster·node 관리자와 workload 생성 권한자는 별도 신뢰 경계다.
7. MySQL server certificate를 발급할 PKI와 CA rotation 담당자를 정한다. certificate SAN에는
   JDBC URL의 host인 `baton-go-mysql`을 반드시 넣고, 필요하면
   `baton-go-mysql.baton-go.svc`와 cluster domain FQDN도 함께 넣는다. IP SAN만으로 대체하지
   않는다.

다음 명령은 Secret 값을 출력하지 않는다.

```bash
kubectl get storageclass
kubectl cluster-info
```

10Gi가 부족할 가능성이 있으면 첫 배포 전에
`deploy/k8s/base/mysql-statefulset.yaml`의 요청량을 조정한다. StatefulSet 생성 뒤에는
volume template을 직접 바꾸기보다 StorageClass의 volume expansion 지원 여부를 확인하고
실제 PVC를 승인된 절차로 확장한다.

## 2. non-secret 설정과 image 고정

`deploy/k8s/overlays/private-server/app-config.properties`에서 세 `REPLACE_ME` 값을 실제
origin으로 바꾼다.

- `BATON_GO_PUBLIC_BASE_URL`: 사용자에게 반환할 short URL의 HTTPS origin
- `BATON_GO_BATON_BASE_URL`: BATON public HTTPS origin
- `BATON_GO_ROUND_BASE_URL`: BATON과 같은 HTTPS origin

비로컬 BATON·ROUND origin은 scheme, host와 port까지 같아야 한다. path, query,
fragment와 userinfo를 넣지 않는다. operations 두 flag는 평상시에 모두 `false`로 둔다.
`SPRING_FLYWAY_ENABLED=false`는 장기 실행 application에서 변경하지 않는다. Flyway는 아래의
일회성 migration Job만 실행한다.

`deploy/k8s/overlays/private-server/kustomization.yaml`의 `registry.invalid/baton-go`와
`replace-with-immutable-release`를 검토·서명된 release image로 교체한다. 이동하는 `latest`
tag 대신 immutable tag를 `newTag`에 사용한다. registry가 digest를 제공하면 `newTag`를
제거하고 같은 image 항목에 `digest: sha256:<검증한 digest>`를 사용한다. digest 문자열을
`newTag` 값에 넣지 않는다. overlay는 선택한 공식 MySQL 8.4.10 multi-architecture image
digest로 고정하며, 변경은 backup·restore 검증을 포함한 별도 DB upgrade로 다룬다.

private registry를 사용하는 기본 overlay는 `imagePullSecret` 이름
`baton-go-registry`를 참조한다. node-level registry 인증이나 미리 적재한 image를 쓰는
환경만 `app-private-registry-patch.yaml` 적용을 제거한다.

렌더링 결과에는 Secret object나 값, certificate와 truststore가 들어가지 않는다. base는 외부
Secret의 이름과 key만 참조하므로 3절의 materialization이 먼저 완료되어야 실제 Pod가
시작한다.

```bash
kubectl kustomize deploy/k8s/overlays/private-server >/dev/null
```

application의 `/tmp`는 64Mi memory `emptyDir`이고 나머지 root filesystem은 read-only다.
Kubernetes `emptyDir`에는 Compose의 `noexec,nosuid,nodev,mode=1777` mount option을 이식성 있게
선언하는 API가 없으므로 두 환경의 hardening이 완전히 같다고 가정하지 않는다. 이 배포는
단일 non-root container만 `/tmp`를 사용한다. 더 강한 mount 정책이 필요한 cluster는 검증된
RuntimeClass 또는 node 정책으로 보완하고 application 기동·파일 쓰기를 다시 시험한다.

## 3. namespace와 Secret 준비

namespace는 workload Kustomization에 의도적으로 포함하지 않았다. 한 번만 별도로 적용한다.

```bash
kubectl apply -k deploy/k8s/bootstrap
```

Secret manager 또는 External Secrets controller를 사용한다면 다음 이름과 key로 materialize한다.

```text
baton-go-runtime-credentials
  BATON_GO_MANAGEMENT_TOKEN
  BATON_GO_LINK_CODE_SECRET

baton-go-database-client-config
  BATON_GO_DB_URL

baton-go-database-runtime-credentials
  BATON_GO_DB_PASSWORD

baton-go-database-migration-credentials
  BATON_GO_DB_MIGRATION_PASSWORD

baton-go-database-bootstrap-credentials
  BATON_GO_DB_ROOT_PASSWORD

baton-go-mysql-server-tls
  ca.pem
  tls.crt
  tls.key

baton-go-mysql-client-tls
  truststore.p12
```

- 관리 token은 32자 이상의 공백 없는 printable ASCII이며, 신규 값은 base64url처럼 HTTP
  header와 parser에 안전한 alphabet으로 생성한다.
- HMAC 비밀은 관리 token과 다른 32자 이상의 새 무작위 값이며 base64url 또는 hex처럼
  parser에 안전한 alphabet으로 생성한다.
- runtime DB user는 변경 불가 ConfigMap의 `baton_go`, migration user는
  `baton_go_migrator`다. BATON 계정이나 `root`를 사용하지 않는다. username 변경은 기존 PVC에
  자동 반영되지 않으므로 일반 설정 변경으로 수행하지 않는다.
- runtime, migration과 root password는 모두 서로 다른 32자 이상의 unpadded base64url 또는
  hex 값으로 생성한다. 신규 DB runtime user init script는 안전한 SQL literal을 위해 이
  alphabet을 강제한다. quote, backslash, whitespace, 제어 문자와 newline을 사용하지 않는다.
- server `tls.crt`의 DNS SAN에는 JDBC host `baton-go-mysql`이 있어야 한다. `tls.key`는 MySQL이
  무인 기동 중 읽을 수 있는 unencrypted PEM으로 발급하되 repository, 작업 log와 backup
  metadata에는 복사하지 않고 Secret encryption·RBAC로 보호한다. `ca.pem`은 해당 server
  certificate를 검증하는 CA chain이다.
- `truststore.p12`에는 공개 server CA certificate만 넣고 private key, client certificate나 다른
  credential을 넣지 않는다. PKCS12 store password는 자격증명이 아니라 공개 CA container의
  호환값인 고정 문자열 `baton-go-public-ca-v1`을 사용한다. application은 이 값을 Hikari driver
  property로 이미 제공한다. Hikari DEBUG가 arbitrary driver property를 출력할 수 있으므로
  이 값을 비밀로 바꾸거나 다른 credential을 같은 property에 넣지 않는다. truststore object의
  변경 무결성과 접근 제한은 Secret at-rest encryption과 RBAC로 보장한다.

```text
jdbc:mysql://baton-go-mysql:3306/baton_go?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/etc/baton-go/mysql-tls/truststore.p12&trustCertificateKeyStoreType=PKCS12&fallbackToSystemTrustStore=false&serverTimezone=UTC
```

`useSSL=false`, `sslMode=DISABLED|PREFERRED`, `allowPublicKeyRetrieval=true`, 다른 host와
`fallbackToSystemTrustStore=true` 및 `trustCertificateKeyStorePassword`를 운영
`BATON_GO_DB_URL`에 넣지 않는다. application과 migration Job은 같은 URL·공개 CA truststore를
사용하되 서로 다른 database username/password를 사용한다.

Kubernetes Secret의 RBAC는 object key 단위가 아니다. 따라서 URL, runtime, migration과 root를
별도 object로 유지한다. 운영자·controller RBAC도 가능하면 `resourceNames`로 필요한 Secret만
허용하며 네 Secret을 하나로 합치지 않는다. Secret read 권한이 없어도 Pod나 workload template을
만들거나 바꿀 수 있으면 해당 Secret을 mount해 읽을 수 있으므로 workload mutation과
`exec/attach/ephemeralcontainers` 권한도 같은 감사 범위에 포함한다.

외부 Secret controller가 없다면 repository 밖의 접근 제한된 파일을 사용할 수 있다.
파일에는 위 key를 `KEY=VALUE` 형식으로 넣고 권한을 `0600`으로 제한한다. 값을 명령 인자,
shell history, 티켓 또는 CI log에 넣지 않는다. `kubectl`은 파일을 데이터로 읽으며 `source`로
실행하지 않는다.

```bash
kubectl -n baton-go create secret generic baton-go-runtime-credentials \
  --from-env-file=/secure/path/baton-go-runtime-credentials.env \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n baton-go create secret generic baton-go-database-client-config \
  --from-env-file=/secure/path/baton-go-database-client-config.env \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n baton-go create secret generic baton-go-database-runtime-credentials \
  --from-env-file=/secure/path/baton-go-database-runtime-credentials.env \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n baton-go create secret generic baton-go-database-migration-credentials \
  --from-env-file=/secure/path/baton-go-database-migration-credentials.env \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n baton-go create secret generic baton-go-database-bootstrap-credentials \
  --from-env-file=/secure/path/baton-go-database-bootstrap-credentials.env \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n baton-go create secret generic baton-go-mysql-server-tls \
  --from-file=ca.pem=/secure/path/mysql-server/ca.pem \
  --from-file=tls.crt=/secure/path/mysql-server/tls.crt \
  --from-file=tls.key=/secure/path/mysql-server/tls.key \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n baton-go create secret generic baton-go-mysql-client-tls \
  --from-file=truststore.p12=/secure/path/mysql-client/truststore.p12 \
  --dry-run=client -o yaml | kubectl apply -f -
```

private registry credential도 전용 Docker config 파일이나 외부 Secret controller로 만든다.
다른 registry credential이 함께 든 개인 Docker config 전체를 복사하지 않는다.

```bash
kubectl -n baton-go create secret generic baton-go-registry \
  --type=kubernetes.io/dockerconfigjson \
  --from-file=.dockerconfigjson=/secure/path/baton-go-registry-config.json \
  --dry-run=client -o yaml | kubectl apply -f -
```

다음 출력은 key 이름과 byte 수만 보여 주며 실제 값은 출력하지 않는다.

```bash
kubectl -n baton-go describe secret baton-go-runtime-credentials
kubectl -n baton-go describe secret baton-go-database-client-config
kubectl -n baton-go describe secret baton-go-database-runtime-credentials
kubectl -n baton-go describe secret baton-go-database-migration-credentials
kubectl -n baton-go describe secret baton-go-database-bootstrap-credentials
kubectl -n baton-go describe secret baton-go-mysql-server-tls
kubectl -n baton-go describe secret baton-go-mysql-client-tls
```

외부 Secret controller를 쓸 때도 key 이름과 파일 format은 같아야 한다. server TLS Secret은
MySQL Pod에만, client truststore는 application과 migration Job에만 mount된다. 실제 Secret
값을 렌더 검증을 위해 임시 manifest에 넣지 않는다.

## 4. 최초 배포

변경 내용을 검토한 뒤 선언적으로 적용한다.

```bash
kubectl diff -k deploy/k8s/overlays/private-server
kubectl apply -k deploy/k8s/overlays/private-server
kubectl -n baton-go rollout status statefulset/baton-go-mysql --timeout=10m
kubectl -n baton-go wait --for=condition=complete \
  job/baton-go-database-migration --timeout=10m
kubectl -n baton-go rollout status deployment/baton-go --timeout=10m
```

Kubernetes는 application, migration Job과 MySQL의 생성 순서를 보장하지 않는다. migration
Job은 DB가 준비될 때까지 실패를 재시도하고 application은 migration 전 schema validation에
실패하면 Pod restart 정책으로 재시도한다. readiness가 성공하기 전에는 Service endpoint가
되지 않는다. Job이 `Complete`가 되지 않으면 application rollout을 성공으로 판단하지 말고
Job Pod의 종료 원인과 MySQL TLS·credential을 먼저 확인한다.

신규 빈 PVC에서 MySQL 공식 image는 다음을 최초 한 번만 수행한다.

- `baton_go` database와 DDL/DML 권한의 `baton_go_migrator` user 생성
- repository init script로 DML 전용 `baton_go` runtime user 생성
- root password 설정
- `MYSQL_ROOT_HOST=localhost`에 따라 원격 root user 생성 생략
- 외부 server certificate로 TLS를 시작하고 TCP 평문 연결 거부

초기화 환경 변수는 이미 database가 든 PVC의 계정이나 password를 갱신하지 않는다. 기존
PVC에는 user 분리 init script도 다시 실행되지 않는다. 이 구조를 기존 PVC에 도입하려면 먼저
승인된 MySQL 관리 채널에서 migration user 생성·권한 부여와 runtime user의 DDL 권한 회수를
완료한 뒤 Secret과 workload를 전환한다. DB Secret만 먼저 바꾸지 않는다. 이 저장소의 첫
private-server 배포는 신규 빈 DB를 전제로 한다.

일회성 Job이 Flyway schema를 만든 뒤 장기 실행 application은 DML user로 JPA schema를
검증한다. 신규 빈 DB의 링크 코드 HMAC guard는 application 시작 시 현재 HMAC 비밀에 자동
결합된다. 이후에는 DB와 `BATON_GO_LINK_CODE_SECRET`을 항상 같은 시점의 복구 단위로
보존한다. key ring을 도입하기 전에는 HMAC 비밀만 회전하지 않는다.

## 5. 접근 경계

base는 `Service/baton-go-http:8080`만 만든다. 이 포트에는 서로 다른 두 경로가 함께 있다.

- public: `GET·HEAD /l/{code}`
- private management: `/api/v1` Prefix 경로 집합

Ingress controller, hostname, TLS 발급 방식과 rate-limit 제품이 정해지지 않았으므로
repository는 Ingress를 만들지 않는다. 환경별 edge는 다음을 강제해야 한다.

아래 `/l/**`, `/api/v1/**` 표기는 경로 집합을 뜻하며 Ingress manifest의 wildcard 문법이
아니다. 표준 Kubernetes Ingress에서는 각각 `path: /l`, `path: /api/v1`과
`pathType: Prefix`를 사용하고 선택한 controller의 실제 matching을 preflight로 검증한다.

application ingress NetworkPolicy는 기본적으로 모든 Pod ingress를 격리한다. `8080` 호출이
필요한 ingress controller 또는 신뢰된 service caller의 namespace에 다음 label을 명시적으로
부여한다. 이 label은 namespace 안의 모든 Pod에 `8080` network reachability를 주므로 서로
신뢰하지 않는 workload와 같은 namespace에 두지 않는다.

```bash
kubectl label namespace <approved-edge-or-caller-namespace> \
  baton-go.networking/allow-http=true
```

1. public HTTPS ingress는 `path: /l`, `pathType: Prefix`만 HTTP Service로 전달하고
   `/api/v1` Prefix를 노출하지 않는다.
2. private management ingress 또는 service-to-service 경로만 `path: /api/v1`,
   `pathType: Prefix`를 전달한다.
3. public edge는 distributed rate limit을 적용하고 access log에서 `/l/{code}`의 code를
   마스킹한다.
4. management Bearer token, Authorization, `Idempotency-Key`, target path와 전체 short URL을
   edge·APM log에 기록하지 않는다.
5. Actuator `8081`은 public/private Ingress에 연결하지 않는다.

`8081`은 위 HTTP label로 열리지 않는다. monitoring Pod에서 직접 scrape해야 한다면 monitoring
namespace와 실제 scraper Pod template에 각각 다음 label을 부여해야 한다. 두 selector는 AND
조건이다. Actuator Service는 기본 생성하지 않으므로 환경별 Pod discovery도 별도 구성한다.

```text
monitoring namespace: baton-go.networking/allow-actuator=true
scraper Pod:          baton-go.networking/actuator-client=true
```

Kubernetes NetworkPolicy 모델에서는 일반적으로 Pod가 실행 중인 node에서 오는 kubelet probe
traffic이 허용되지만, CNI host firewall·strict policy와 host-network 구현은 다를 수 있다.
최초 배포 전에 startup/liveness/readiness probe가 실제 CNI에서 통과하는지 확인한다. 차단되면
승인된 node CIDR 또는 CNI 전용 host endpoint 정책만 좁게 추가하고 `8081`을
`0.0.0.0/0`이나 전체 cluster namespace에 열지 않는다. host-network ingress가 node traffic으로
보여 namespaceSelector를 우회하는지도 별도로 검증한다.

Actuator Service를 기본 생성하지 않아 kubelet probe 외의 안정된 cluster endpoint가 없다.
일시적인 운영 확인은 CNI에서 허용되는 로컬 port-forward로 수행하고 종료한다. strict CNI가
port-forward도 차단하면 위 label을 가진 일회성 운영 Pod를 사용한다.

```bash
kubectl -n baton-go port-forward deployment/baton-go 18081:8081
curl --fail --silent http://127.0.0.1:18081/actuator/health/readiness
```

MySQL NetworkPolicy는 같은 namespace의 BATON GO application과 database-migration label에서
오는 TCP 3306만 허용한다. MySQL 자체도 `require_secure_transport=ON`이며 application과 Job은
`VERIFY_IDENTITY`로 server DNS를 검증한다. NetworkPolicy를 집행하지 않는 CNI에서는 selector
차단 효과가 없지만 TLS를 비활성화해 보완하지 않는다.

## 6. 배포 검증

다음 상태를 확인한다.

```bash
kubectl -n baton-go get deployment,statefulset,job,pod,service,pvc,networkpolicy
kubectl -n baton-go get events --sort-by=.lastTimestamp
```

- MySQL과 application Pod가 `Ready`다.
- database migration Job이 `Complete`이며 실패한 이전 Pod 원인이 남아 있지 않다.
- `data-baton-go-mysql-0` PVC가 `Bound`다.
- HTTP Service만 존재하며 MySQL Service는 headless다.
- application container의 Secret 참조에는 migration·root password가 없고 migration Job에는
  runtime·root password가 없다.
- MySQL은 `require_secure_transport=ON`이고 승인된 session의 `Ssl_cipher`가 비어 있지 않다.
  별도 격리 검증에서는 잘못된 CA 또는 JDBC host로 `VERIFY_IDENTITY` 연결이 실패한다. 인증서와
  JDBC URL 전체는 evidence에 기록하지 않는다.
- 승인하지 않은 namespace에서 application `8080`, 일반 Pod에서 Actuator `8081`, application과
  migration label이 아닌 Pod에서 MySQL `3306` 연결이 실패한다.
- 평상시 operations 두 flag는 `false`다.

그 다음 private management 경로에서 새 canonical UUID intent 한 건으로 생성, 같은 intent
재생, 공개 resolver와 폐기를 검증한다. 실제 management token, `Idempotency-Key`, 공개 code,
target path와 전체 short URL을 shell history나 검증 증거에 복사하지 않는다. 증거에는 HTTP
status, link ID, request ID와 timestamp처럼 허용된 metadata만 남긴다.

## 7. 업데이트와 rollback

application image와 non-secret ConfigMap 변경은 Kustomize를 다시 적용한다. ConfigMap 이름에
content hash가 붙어 Pod template이 바뀌므로 설정 변경도 새 rollout을 만든다. migration Job의
Pod template은 immutable이고 같은 release image를 사용한다. 이전 Job이 남아 있으면 먼저
완료·실패 상태와 필요한 비밀 제외 metadata를 보존하고, 실행 중이 아님을 확인한 뒤 Job만
삭제한다. `ttlSecondsAfterFinished=3600`은 보조 정리이며 release 직전 삭제 확인을 대신하지
않는다.

```bash
kubectl -n baton-go get job baton-go-database-migration
kubectl -n baton-go delete job baton-go-database-migration --wait=true
kubectl diff -k deploy/k8s/overlays/private-server
kubectl apply -k deploy/k8s/overlays/private-server
kubectl -n baton-go wait --for=condition=complete \
  job/baton-go-database-migration --timeout=10m
kubectl -n baton-go rollout status deployment/baton-go --timeout=10m
```

위 삭제는 completed/failed migration Job object만 대상으로 하며 StatefulSet, PVC, namespace와
Secret에는 사용하지 않는다. Job이 아직 실행 중이면 중단하지 말고 원인을 확인한다. 한 번의
Kustomize apply는 Job과 Deployment 순서를 보장하지 않으므로 모든 Flyway 변경은 구 application과
구 schema에 호환되는 expand 단계여야 한다. Job `Complete` 확인 뒤 새 application을 검증하고,
column/table 제거 같은 contract 단계는 모든 구 Pod와 reader가 사라진 후 별도 release에서
수행한다. application이 migration보다 먼저 시작해 schema validation에 실패하는 짧은 구간은
restart로 복구되지만, 이를 destructive migration 허용 근거로 사용하지 않는다.

단, V5의 `public_origin` 추가는 column 모양만 expand-compatible이고 구 writer와 동작까지
온라인 호환되지는 않는다. V5 적용 뒤 구 Pod가 생성한 예약은 `public_origin`이 `NULL`인 채
최초 요청을 성공시킬 수 있고 새 Pod는 그 intent의 정확한 short URL을 재생할 수 없다. 이
private-server의 신규 빈 DB 첫 배포에는 구 Pod가 없으므로 해당 경합이 없지만, 이미 application이
실행 중인 환경에 V5를 도입할 때는 다음 maintenance 순서를 사용한다.

1. private management edge와 모든 BATON 호출자에서 링크 생성 writer를 차단하고 in-flight
   요청과 outbox 전송을 drain한다. public resolver read는 drain 중과 구 Pod가 남아 있는
   동안에만 계속 운영할 수 있다.
2. `Deployment/baton-go`를 replica 0으로 scale하고 구 Pod가 0개이며 생성 writer가 남지 않았음을
   확인한다. replica 0 확인 시점부터 5단계에서 새 Deployment readiness가 회복될 때까지
   public resolver도 계획 중단 상태다.
3. V5 이전 예약의 최초 origin inventory를 확인한다. 증명할 수 있는 행만 canonical origin으로
   maintenance backfill하고, 증거가 없는 행을 현재 설정으로 일괄 추정하지 않는다.
4. 완료된 이전 migration Job을 위 절차로 삭제한 뒤 Kustomize를 적용한다. 새 application이 먼저
   시작하더라도 edge writer 차단은 유지한다.
5. migration Job `Complete`, 새 Deployment readiness, 신규 intent의 최초 생성과 동일 URL 재생을
   순서대로 확인한 뒤 writer를 다시 연다.

```bash
kubectl -n baton-go scale deployment/baton-go --replicas=0
kubectl -n baton-go wait --for=delete \
  pod -l app.kubernetes.io/name=baton-go,app.kubernetes.io/component=application \
  --timeout=10m
```

이 예외 절차를 일반 Flyway 변경의 writer 중지 근거로 확대하지 않는다. 이후 migration은 다시
expand/contract 호환 규칙을 따른다.

Secret을 environment variable로 주입한 running Pod는 Secret object가 바뀌어도 값을 자동으로
다시 읽지 않는다. Secret 회전은 다음 수명주기별 절차를 따른다.

- management token: runtime Secret의 해당 key를 갱신하고
  `kubectl -n baton-go rollout restart deployment/baton-go`를 실행한다. rollout 뒤 새 token은
  성공하고 이전 token은 `401`인지 private 경로에서 확인한다.
- DB runtime password: backup과 maintenance window를 확보하고 승인된 MySQL 관리 채널에서
  `baton_go` account를 새 password로 바꾼 뒤 runtime Secret을 같은 값으로 갱신한다. 이어서
  `kubectl -n baton-go rollout restart statefulset/baton-go-mysql`과 rollout status를 먼저
  완료하고, `kubectl -n baton-go rollout restart deployment/baton-go`와 readiness 검증을
  수행한다. probe도 DB password를 사용하므로 StatefulSet restart를 생략하지 않는다.
- DB migration password: 실행 중인 Job이 없는 maintenance window에 `baton_go_migrator`
  account를 먼저 변경하고 migration Secret을 갱신한다. 다음 migration Job의 성공으로 새 값을
  검증한다. 일반 application은 이 Secret을 참조하지 않는다.
- DB root password: local root account를 승인된 관리 채널에서 먼저 변경하고 bootstrap
  Secret을 갱신한 뒤 MySQL StatefulSet을 restart한다. application과 migration Job에는 root
  key를 주입하지 않는다.
- MySQL TLS/CA: CA overlap 기간을 두고 다음 순서를 지킨다. 먼저 old+new CA를 함께 담은 dual
  truststore와 필요하면 갱신한 client-config JDBC URL을 배포하고 application을 rollout해 기존
  server certificate 연결과 readiness를 확인한다. 실행 중인 migration Job은 없어야 하며 다음
  Job도 이 dual truststore를 사용한다. 그 다음 새 CA가 서명한 server certificate Secret을
  적용하고 MySQL을 restart해 application 재연결, `Ssl_cipher`, 잘못된 CA·host negative test를
  확인한다. 마지막으로 old CA를 제거한 client truststore를 배포하고 application을 다시
  rollout한 뒤에만 overlap을 종료한다. server-first로 회전하거나 Secret volume 파일 변경만으로
  MySQL/Hikari가 인증서를 즉시 reload한다고 가정하지 않는다.
- HMAC 비밀: key ring 도입 전에는 일반 회전 대상으로 취급하지 않는다. DB와 함께 검증된
  restore를 수행하는 경우에만 같은 Secret version을 사용한다.

DB password 회전은 단일 account의 old/new password 전환 구간에 짧은 연결 실패가 생길 수
있으므로 maintenance 작업으로 수행한다. 무중단 dual-password 정책이 필요하면 MySQL account
정책과 폐기 시점을 별도 ADR/runbook으로 먼저 정의한다. 실제 password나 SQL literal은
shell history, process argument와 작업 log에 남기지 않는다.

application rollback은 이전 immutable image와 그 release가 기대한 설정·schema 호환성을
확인한 뒤 수행한다.

```bash
kubectl -n baton-go rollout history deployment/baton-go
kubectl -n baton-go rollout undo deployment/baton-go
```

Flyway migration은 자동으로 역적용되지 않는다. DB schema를 되돌리려고 적용된 migration을
수정하거나 PVC를 과거 snapshot으로 단독 복구하지 않는다.

## 8. backup과 restore

각 backup evidence는 최소한 다음을 하나의 식별 가능한 복구 세트로 묶는다.

- MySQL-aware logical/physical backup 또는 일관성이 검증된 volume snapshot
- 그 시점의 `BATON_GO_LINK_CODE_SECRET` secret-manager version
- application release image digest와 Flyway schema version
- DB client-config, runtime, migration과 bootstrap credential의 secret-manager version
- MySQL server certificate·CA와 client truststore version
- public/BATON/ROUND origin 설정 version

restore rehearsal은 격리된 namespace와 별도 hostname에서 수행한다. 복구한 DB account와
runtime·migration·bootstrap Secret version을 맞추고, 복구 환경 Service DNS를 SAN에 포함한
server certificate와 그 CA truststore를 주입한다. migration Job `Complete` 뒤 같은 HMAC Secret
version으로 startup guard, readiness와 보관된 canary intent의 동일 URL 재생을 검증한다. 전체
short URL, code hash, HMAC fingerprint, JDBC URL이나 Secret 값은 restore evidence에 남기지
않는다.

다음 작업은 일반적인 rollback이나 정리 명령으로 사용하지 않는다.

- `kubectl delete namespace baton-go`
- `kubectl delete pvc data-baton-go-mysql-0`
- 검증되지 않은 snapshot으로 PVC만 되돌리기
- DB 없이 HMAC Secret만 과거 또는 새 값으로 바꾸기

StatefulSet 삭제는 기본적으로 PVC를 보존하지만 namespace 삭제는 namespaced PVC를 함께
삭제하고 StorageClass reclaim policy에 따라 실제 volume까지 잃을 수 있다. namespace manifest를
workload Kustomization에서 분리한 이유도 이 연쇄 삭제를 줄이기 위해서다.

## 9. operations maintenance와 production gate

target-contract inventory가 필요한 maintenance window에서만 private edge 차단 증거와 writer
중지를 먼저 확인한 뒤 operations 두 flag를 함께 활성화한다. flag는 network boundary가
아니며 작업 직후 다시 `false`로 배포한다. 상세 절차는
`docs/RUNBOOK/target-contract-v1-remediation.md`를 따른다.

최종 public production 공개 전에는 다음이 여전히 별도 완료 조건이다.

- 배포 DB 전체 inventory와 `unrevoked non-compliant=0`, 미승인 `HOLD=0` 증거
- BATON session·CSRF·participation grant와 room mapping/tombstone
- public `/l` Prefix 전용 edge, private `/api/v1` Prefix 경계와 access-log 마스킹
- 호출자 outbox/cancel tombstone과 분산 rate limit
- 실제 backup restore rehearsal과 장애·rollback 훈련

참고:

- [Kubernetes Kustomize](https://kubernetes.io/docs/tasks/manage-kubernetes-objects/kustomization/)
- [Kubernetes StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
- [Kubernetes probe](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- [Kubernetes NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
- [Kubernetes Secret 보안 권고](https://kubernetes.io/docs/concepts/security/secrets-good-practices/)
- [MySQL 공식 image 초기화 변수](https://hub.docker.com/_/mysql)
- [MySQL Connector/J TLS 설정](https://dev.mysql.com/doc/connectors/en/connector-j-connp-props-security.html)
