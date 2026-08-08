# Private Kubernetes 배포 runbook

이 문서는 BATON GO application과 GO 전용 MySQL을 private Kubernetes에 처음 배포하고
업데이트·복구하는 절차다. BATON 또는 ROUND의 database, user, volume이나 Secret은 사용하지
않는다. 저장소의 Kustomize overlay는 다음 경계를 만든다.

| 자원 | 역할 | 기본 노출 |
|---|---|---|
| `Deployment/baton-go` | 링크 생성·조회·폐기와 공개 resolver | `ClusterIP:8080` |
| `StatefulSet/baton-go-mysql` | GO 전용 `baton_go` database | headless `ClusterIP:3306` |
| `PVC/data-baton-go-mysql-0` | GO MySQL data directory | `ReadWriteOnce`, 10Gi |
| `ConfigMap/baton-go-database-identity` | 변경 불가 GO DB username | application·MySQL이 함께 참조 |
| `Secret/baton-go-runtime-credentials` | 관리 Bearer token, 링크 HMAC 비밀 | application Pod만 참조 |
| `Secret/baton-go-database-credentials` | GO DB application/root password | root password는 MySQL Pod만 참조 |

이 배포만으로 public production rollout이 승인되지는 않는다. PRD-0003의 BATON session,
participation grant, room mapping, edge routing과 기존 데이터 inventory gate는 별도다.

## 1. 배포 전 결정

다음을 먼저 확정한다.

1. cluster에 default StorageClass가 있고 `ReadWriteOnce` PVC를 동적 provision할 수 있는지
   확인한다. 특정 `local-path`, CSI 또는 `hostPath`는 저장소 매니페스트에 고정하지 않는다.
2. registry에서 node가 가져올 수 있는 immutable BATON GO release image를 준비한다.
3. public HTTPS origin과 BATON·ROUND의 shared HTTPS origin을 확정한다.
4. 사용하는 CNI가 Kubernetes NetworkPolicy를 집행하는지 확인한다. 지원하지 않으면
   MySQL 접근 제한을 firewall 또는 CNI 정책으로 별도 보완한다.
5. DB backup 대상, 보존 기간, restore 담당자와 HMAC Secret version의 공동 복구 단위를
   확정한다.
6. Kubernetes Secret의 at-rest encryption을 활성화하고, `baton-go` namespace에서 Secret
   `get/list/watch`와 Pod `exec` 권한을 배포·운영 주체에만 최소 부여한다. Secret object는
   base64 encoding만으로 보호되지 않으며 cluster·node 관리자는 별도 신뢰 경계다.

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

`deploy/k8s/overlays/private-server/kustomization.yaml`의 `registry.invalid/baton-go`와
`replace-with-immutable-release`를 검토·서명된 release image로 교체한다. 이동하는 `latest`
tag 대신 immutable tag를 `newTag`에 사용한다. registry가 digest를 제공하면 `newTag`를
제거하고 같은 image 항목에 `digest: sha256:<검증한 digest>`를 사용한다. digest 문자열을
`newTag` 값에 넣지 않는다. overlay는 선택한 공식 MySQL 8.4.10 multi-architecture image
digest로 고정하며, 변경은 backup·restore 검증을 포함한 별도 DB upgrade로 다룬다.

private registry를 사용하는 기본 overlay는 `imagePullSecret` 이름
`baton-go-registry`를 참조한다. node-level registry 인증이나 미리 적재한 image를 쓰는
환경만 `app-private-registry-patch.yaml` 적용을 제거한다.

렌더링 결과에 Secret 값은 들어가지 않는다.

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

baton-go-database-credentials
  BATON_GO_DB_PASSWORD
  BATON_GO_DB_ROOT_PASSWORD
```

- 관리 token은 32자 이상의 공백 없는 printable ASCII이며, 신규 값은 base64url처럼 HTTP
  header와 parser에 안전한 alphabet으로 생성한다.
- HMAC 비밀은 관리 token과 다른 32자 이상의 새 무작위 값이며 base64url 또는 hex처럼
  parser에 안전한 alphabet으로 생성한다.
- DB user는 변경 불가 `baton-go-database-identity` ConfigMap의 `baton_go`이며 BATON 계정이나
  `root`를 사용하지 않는다. username 변경은 기존 PVC에 자동 반영되지 않으므로 일반 설정
  변경으로 수행하지 않는다.
- application password와 root password는 서로 다른 충분히 긴 base64url 또는 hex 값으로
  생성한다. MySQL 공식 image의 최초 초기화 SQL은 password를 SQL literal에 넣으므로 quote,
  backslash, 제어 문자와 newline이 포함된 값을 신규 DB 초기화에 사용하지 않는다.

외부 Secret controller가 없다면 repository 밖의 접근 제한된 파일을 사용할 수 있다.
파일에는 위 key를 `KEY=VALUE` 형식으로 넣고 권한을 `0600`으로 제한한다. 값을 명령 인자,
shell history, 티켓 또는 CI log에 넣지 않는다. `kubectl`은 파일을 데이터로 읽으며 `source`로
실행하지 않는다.

```bash
kubectl -n baton-go create secret generic baton-go-runtime-credentials \
  --from-env-file=/secure/path/baton-go-runtime-credentials.env \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n baton-go create secret generic baton-go-database-credentials \
  --from-env-file=/secure/path/baton-go-database-credentials.env \
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
kubectl -n baton-go describe secret baton-go-database-credentials
```

## 4. 최초 배포

변경 내용을 검토한 뒤 선언적으로 적용한다.

```bash
kubectl diff -k deploy/k8s/overlays/private-server
kubectl apply -k deploy/k8s/overlays/private-server
kubectl -n baton-go rollout status statefulset/baton-go-mysql --timeout=10m
kubectl -n baton-go rollout status deployment/baton-go --timeout=10m
```

Kubernetes는 application과 MySQL의 생성 순서를 보장하지 않는다. application이 먼저
시작해 DB 연결에 실패하면 Pod restart 정책으로 재시도하며, readiness가 성공하기 전에는
Service endpoint가 되지 않는다. 이를 위해 별도 wait script나 init container는 추가하지
않는다.

신규 빈 PVC에서 MySQL 공식 image는 다음을 최초 한 번만 수행한다.

- `baton_go` database와 GO application user 생성
- root password 설정
- `MYSQL_ROOT_HOST=localhost`에 따라 원격 root user 생성 생략

초기화 환경 변수는 이미 database가 든 PVC의 계정이나 password를 갱신하지 않는다. 기존
PVC에서 `baton-go-database-credentials`만 바꾸면 application과 DB가 불일치한다. DB
credential 회전은 승인된 MySQL account 변경과 Secret 갱신 뒤 7절의
MySQL StatefulSet → application Deployment rollout·검증 순서를 하나의 maintenance
절차로 수행한다.

Flyway가 schema를 만들고 신규 빈 DB의 링크 코드 HMAC guard는 현재 HMAC 비밀에 자동
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

1. public HTTPS ingress는 `path: /l`, `pathType: Prefix`만 HTTP Service로 전달하고
   `/api/v1` Prefix를 노출하지 않는다.
2. private management ingress 또는 service-to-service 경로만 `path: /api/v1`,
   `pathType: Prefix`를 전달한다.
3. public edge는 distributed rate limit을 적용하고 access log에서 `/l/{code}`의 code를
   마스킹한다.
4. management Bearer token, Authorization, `Idempotency-Key`, target path와 전체 short URL을
   edge·APM log에 기록하지 않는다.
5. Actuator `8081`은 public/private Ingress에 연결하지 않는다.

Actuator Service를 기본 생성하지 않아 kubelet probe 외의 안정된 cluster endpoint가 없다.
일시적인 운영 확인은 로컬 port-forward로 수행하고 종료한다.

```bash
kubectl -n baton-go port-forward deployment/baton-go 18081:8081
curl --fail --silent http://127.0.0.1:18081/actuator/health/readiness
```

MySQL NetworkPolicy는 같은 namespace의 BATON GO application label에서 오는 TCP 3306만
허용한다. NetworkPolicy를 집행하지 않는 CNI에서는 매니페스트가 존재해도 차단 효과가 없다.

## 6. 배포 검증

다음 상태를 확인한다.

```bash
kubectl -n baton-go get deployment,statefulset,pod,service,pvc
kubectl -n baton-go get events --sort-by=.lastTimestamp
```

- MySQL과 application Pod가 `Ready`다.
- `data-baton-go-mysql-0` PVC가 `Bound`다.
- HTTP Service만 존재하며 MySQL Service는 headless다.
- application Secret 참조에는 root password가 없다.
- 평상시 operations 두 flag는 `false`다.

그 다음 private management 경로에서 새 canonical UUID intent 한 건으로 생성, 같은 intent
재생, 공개 resolver와 폐기를 검증한다. 실제 management token, `Idempotency-Key`, 공개 code,
target path와 전체 short URL을 shell history나 검증 증거에 복사하지 않는다. 증거에는 HTTP
status, link ID, request ID와 timestamp처럼 허용된 metadata만 남긴다.

## 7. 업데이트와 rollback

application image와 non-secret ConfigMap 변경은 Kustomize를 다시 적용한다. ConfigMap 이름에
content hash가 붙어 Pod template이 바뀌므로 설정 변경도 새 rollout을 만든다.

```bash
kubectl diff -k deploy/k8s/overlays/private-server
kubectl apply -k deploy/k8s/overlays/private-server
kubectl -n baton-go rollout status deployment/baton-go --timeout=10m
```

Secret을 environment variable로 주입한 running Pod는 Secret object가 바뀌어도 값을 자동으로
다시 읽지 않는다. Secret 회전은 다음 수명주기별 절차를 따른다.

- management token: runtime Secret의 해당 key를 갱신하고
  `kubectl -n baton-go rollout restart deployment/baton-go`를 실행한다. rollout 뒤 새 token은
  성공하고 이전 token은 `401`인지 private 경로에서 확인한다.
- DB application password: backup과 maintenance window를 확보하고 승인된 MySQL 관리 채널에서
  `baton_go` account를 새 password로 바꾼 뒤 DB Secret을 같은 값으로 갱신한다. 이어서
  `kubectl -n baton-go rollout restart statefulset/baton-go-mysql`과 rollout status를 먼저
  완료하고, `kubectl -n baton-go rollout restart deployment/baton-go`와 readiness 검증을
  수행한다. probe도 DB password를 사용하므로 StatefulSet restart를 생략하지 않는다.
- DB root password: local root account를 승인된 관리 채널에서 먼저 변경하고 DB Secret의
  root key를 갱신한 뒤 MySQL StatefulSet을 restart한다. application에는 root key를 주입하지
  않는다.
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
- DB application/root credential의 secret-manager version
- public/BATON/ROUND origin 설정 version

restore rehearsal은 격리된 namespace와 별도 hostname에서 수행한다. 복구한 DB에 같은 HMAC
Secret version을 주입하고 startup guard, readiness와 보관된 canary intent의 동일 URL 재생을
검증한다. 전체 short URL, code hash, HMAC fingerprint나 Secret 값은 restore evidence에 남기지
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
