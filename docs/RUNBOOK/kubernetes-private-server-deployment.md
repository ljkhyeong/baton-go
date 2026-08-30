# 비공개 Kubernetes 서버 배포 운영 절차

이 문서는 BATON GO 애플리케이션과 GO 전용 MySQL을 비공개 Kubernetes에 처음 배포하고
업데이트·복구하는 절차다. BATON 또는 ROUND의 데이터베이스, 사용자, 볼륨이나 Secret은 사용하지
않는다. 저장소의 Kustomize overlay는 다음 경계를 만든다.

| 자원 | 역할 | 기본 노출 |
|---|---|---|
| `Deployment/baton-go` | 링크 생성·조회·폐기와 공개 해석 | `ClusterIP:8080` |
| `Job/baton-go-database-migration` | 배포 이미지의 Flyway 마이그레이션 전용 실행 | 일회성, 네트워크 엔드포인트 없음 |
| `StatefulSet/baton-go-mysql` | GO 전용 `baton_go` 데이터베이스 | 헤드리스 `ClusterIP:3306` |
| `PVC/data-baton-go-mysql-0` | GO MySQL 데이터 디렉터리 | `ReadWriteOnce`, 10Gi |
| `ConfigMap/baton-go-database-identity` | 변경 불가 런타임·마이그레이션 사용자 이름 | 애플리케이션·Job·MySQL이 참조 |
| `Secret/baton-go-link-code-secret` | 링크 HMAC 비밀값 | 애플리케이션 Pod만 참조 |
| `Secret/baton-go-database-client-config` | 비밀 쿼리가 없는 TLS JDBC URL | 애플리케이션·마이그레이션 Job만 참조 |
| `Secret/baton-go-database-runtime-credentials` | DML 런타임 비밀번호 | 애플리케이션·MySQL만 참조 |
| `Secret/baton-go-database-migration-credentials` | DDL 마이그레이션 비밀번호 | 마이그레이션 Job·MySQL 초기화만 참조 |
| `Secret/baton-go-database-bootstrap-credentials` | 로컬 root 비밀번호 | MySQL Pod만 참조 |
| `Secret/baton-go-mysql-server-tls` | MySQL CA, 서버 인증서와 개인 키 | MySQL Pod만 마운트 |
| `Secret/baton-go-mysql-client-tls` | 공개 CA만 든 Connector/J PKCS12 신뢰 저장소 | 애플리케이션·마이그레이션 Job만 마운트 |
| `ConfigMap/baton-go-mysql-runtime-user-init-*` | DML 전용 런타임 사용자 초기화 스크립트 | MySQL 초기화 디렉터리에 마운트 |

이 배포만으로 공개 운영 배포가 승인되지는 않는다. PRD-0003의 BATON 세션,
참여 허가, 회의실 매핑, 외부 경계 라우팅과 기존 데이터 목록 조사 관문은 별도다.

## 1. 배포 전 결정

다음을 먼저 확정한다.

1. 클러스터에 기본 StorageClass가 있고 `ReadWriteOnce` PVC를 동적 할당할 수 있는지
   확인한다. 특정 `local-path`, CSI 또는 `hostPath`는 저장소 매니페스트에 고정하지 않는다.
2. 레지스트리에서 노드가 가져올 수 있는 변경 불가 BATON GO 배포 이미지를 준비한다.
3. 공개 HTTPS 출처와 BATON·ROUND의 공용 HTTPS 출처를 확정한다.
4. 사용하는 CNI가 Kubernetes NetworkPolicy와 노드-to-Pod kubelet probe를 어떻게 집행하는지
   확인한다. 현재 저장소 정책은 ingress만 제한하므로 클러스터 DNS와 MySQL 외의
   egress를 기본 차단할지, DNS selector·IP·포트를 해당 CNI에서 어떻게 표현할지
   결정한다. NetworkPolicy를 지원하지 않으면 애플리케이션·MySQL 접근 제한을
   방화벽 또는 CNI 정책으로 별도 보완한다. host-network ingress controller를 쓰면
   출처 selector 적용 여부도 확인한다.
5. 플랫폼 운영 담당자가 소유할 DB 백업 정책을 확정한다. 정책에는 RPO, RTO, 실행 주기,
   보존 기간, 담당자, 실패 경보와 증거 저장 위치를 포함하고 HMAC Secret 버전을 DB와 같은
   복구 단위로 관리한다.
6. Kubernetes Secret의 저장 시 암호화를 활성화한다. `baton-go` Namespace의 Secret
   `get/list/watch`, Pod `exec/attach/ephemeralcontainers`, Pod·Deployment·StatefulSet·Job의
   `create/update/patch`는 직접 또는 워크로드 마운트를 통해 Secret을 읽을 수 있는 권한으로
   취급하고 승인된 배포·운영 주체에만 최소 부여한다. Secret 객체는 base64 인코딩만으로
   보호되지 않으며 클러스터·노드 관리자와 워크로드 생성 권한자는 별도 신뢰 경계다.
7. MySQL 서버 인증서를 발급할 PKI와 CA 회전 담당자를 정한다. 인증서 SAN에는
   JDBC URL의 host인 `baton-go-mysql`을 반드시 넣고, 필요하면
   `baton-go-mysql.baton-go.svc`와 클러스터 도메인 FQDN도 함께 넣는다. IP SAN만으로 대체하지
   않는다.
8. 멱등 생성 요청, 만료·폐기 링크의 보존 기간과 자동 정리 후 HTTP 의미,
   백업·감사 요구, PVC 경보·증설 기준을 하나의 데이터 수명주기 정책으로 결정한다.
   이 결정 전에는 만료·폐기를 자동 삭제 조건으로 사용하지 않는다.
9. 관리 JWT 발급자, `aud=baton-go`, 서비스 신원별 scope와 signing key 회전 담당자를
   확정한다. 애플리케이션 Pod에서 설정한 JWK Set HTTPS 주소에 접근할 수 있어야 한다.
10. Prometheus 수집 구성, 경보 규칙, 알림 경로와 응답 담당자를 확정한다. Actuator
   endpoint 노출이 실제 수집·경보 연결을 대신하지 않는다.

다음 명령은 Secret 값을 출력하지 않는다.

```bash
kubectl get storageclass
kubectl cluster-info
```

10Gi가 부족할 가능성이 있으면 첫 배포 전에
`deploy/k8s/base/mysql-statefulset.yaml`의 요청량을 조정한다. StatefulSet 생성 뒤에는
볼륨 템플릿을 직접 바꾸기보다 StorageClass의 볼륨 확장 지원 여부를 확인하고
실제 PVC를 승인된 절차로 확장한다.

## 2. 비밀값 제외 설정과 이미지 고정

`deploy/k8s/overlays/private-server/app-config.properties`에서 다섯 `REPLACE_ME` 값을 실제
출처로 바꾼다.

- `BATON_GO_PUBLIC_BASE_URL`: 사용자에게 반환할 단축 URL의 HTTPS 출처
- `BATON_GO_BATON_BASE_URL`: BATON 공개 HTTPS 출처
- `BATON_GO_ROUND_BASE_URL`: BATON과 같은 HTTPS 출처
- `BATON_GO_MANAGEMENT_JWT_ISSUER_URI`: 관리 서비스 JWT의 HTTPS 발급자
- `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI`: 발급자의 HTTPS JWK Set 주소

`BATON_GO_MANAGEMENT_JWT_AUDIENCE`는 발급 계약을 별도로 정하지 않았다면 `baton-go`를 유지한다.
빈 값으로 설정하지 않는다. 최종 audience 목록이 비어 있거나 빈 값·공백뿐인 항목을 포함하면
애플리케이션이 시작되지 않는다.
JWK Set 주소를 명시하면 애플리케이션 시작이 discovery 서버 가용성에 묶이지 않으면서 설정한
`iss` 검증은 유지된다.

비로컬 BATON·ROUND 출처는 scheme, host와 port까지 같아야 한다. 경로, query,
fragment와 userinfo를 넣지 않는다. 운영 기능 두 설정값은 평상시에 모두 `false`로 둔다.
`SPRING_FLYWAY_ENABLED=false`는 장기 실행 애플리케이션에서 변경하지 않는다. Flyway는 아래의
일회성 마이그레이션 Job만 실행한다.

`deploy/k8s/overlays/private-server/kustomization.yaml`의 `registry.invalid/baton-go`를 실제
레지스트리 이미지 이름으로, `REPLACE_ME_WITH_IMMUTABLE_RELEASE_DIGEST`를 검토·서명한
애플리케이션 이미지의 64자리 SHA-256 digest로 교체한다. 애플리케이션 Deployment와
마이그레이션 Job은 같은 `name@sha256:digest`를 사용하며 태그 배포는 허용하지 않는다.
오버레이는 선택한 공식 MySQL 8.4.11 다중 아키텍처 이미지도 digest로 고정하며, 변경은
백업·복원 검증을 포함한 별도 DB 업그레이드로 다룬다.

릴리스마다 애플리케이션 이미지 다이제스트와 함께 SBOM, 취약점 검사 결과,
서명·provenance 검증 결과를 배포 증거에 보존한다. 조직의 취약점 수용 정책을 통과하지
못했거나 서명 주체·소스 커밋·빌드 정보를 대조할 수 없는 이미지는 배포하지 않는다.
이미지 태그, 로컬 빌드 성공과 레지스트리 화면만은 변경 불가 증거가 아니다.

비공개 레지스트리를 사용하는 기본 오버레이는 `imagePullSecret` 이름
`baton-go-registry`를 참조한다. 노드 수준 레지스트리 인증이나 미리 적재한 이미지를 쓰는
환경만 `app-private-registry-patch.yaml` 적용을 제거한다.

렌더링 결과에는 Secret 객체나 값, 인증서와 신뢰 저장소가 들어가지 않는다. 기본본은 외부
Secret의 이름과 키만 참조하므로 3절의 구체화가 먼저 완료되어야 실제 Pod가
시작한다.

```bash
set -eu

if grep -F -q \
  -e 'REPLACE_ME' \
  -e 'registry.invalid/baton-go' \
  -e 'newTag:' \
  deploy/k8s/overlays/private-server/app-config.properties \
  deploy/k8s/overlays/private-server/kustomization.yaml; then
  echo "배포 placeholder와 가짜 이미지 참조를 교체하고 tag 설정을 제거해야 합니다." >&2
  exit 1
fi

app_digest="$(awk '
  $1 == "-" && $2 == "name:" {
    in_app = $3 == "baton-go"
    next
  }
  in_app && $1 == "digest:" {
    print $2
    exit
  }
' deploy/k8s/overlays/private-server/kustomization.yaml)"
if ! printf '%s\n' "$app_digest" | grep -Eq '^sha256:[0-9a-f]{64}$'; then
  echo "애플리케이션 이미지는 64자리 SHA-256 digest로 고정해야 합니다." >&2
  exit 1
fi

kubectl kustomize deploy/k8s/overlays/private-server >/dev/null
```

이 점검은 placeholder·가짜 이미지·tag 설정 제거, 애플리케이션 이미지 digest 형식과
Kustomize 렌더 가능 여부를 확인한다. URL의 정규 HTTPS 출처·동일 출처 정책은 애플리케이션 시작
검증이 소유하며, 이미지 서명·SBOM·취약점 수용 여부는 위 릴리스 증거와 별도로 대조한다.

애플리케이션의 `/tmp`는 64Mi 메모리 `emptyDir`이고 나머지 root 파일 시스템은 읽기 전용이다.
Kubernetes `emptyDir`에는 Compose의 `noexec,nosuid,nodev,mode=1777` 마운트 옵션을 이식성 있게
선언하는 API가 없으므로 두 환경의 보안 강화가 완전히 같다고 가정하지 않는다. 이 배포는
단일 non-root 컨테이너만 `/tmp`를 사용한다. 더 강한 마운트 정책이 필요한 클러스터는 검증된
RuntimeClass 또는 노드 정책으로 보완하고 애플리케이션 기동·파일 쓰기를 다시 시험한다.

## 3. Namespace와 Secret 준비

Namespace는 워크로드 Kustomization에 의도적으로 포함하지 않았다. 한 번만 별도로 적용한다.

```bash
kubectl apply -k deploy/k8s/bootstrap
```

기본 Namespace는 `restricted` 감사·경고와 `baseline` 강제를 사용한다. 애플리케이션,
마이그레이션 Job과 MySQL 매니페스트는 모두 비루트 사용자, `RuntimeDefault` seccomp,
권한 상승 금지와 Linux capability 전체 제거를 선언한다. MySQL은 공식 이미지의 `mysql`
사용자와 같은 UID/GID `999`를 사용하고 데이터 PVC는 `fsGroup=999`로 연결한다.

`restricted` 강제 승격 전에는 고정한 MySQL 이미지 다이제스트로 신규 PVC, 운영 백업에서
복원한 PVC와 현재 PVC를 각각 검증한다. 세 경우 모두 MySQL UID/GID가 `999`, 유효 capability가
비어 있고 TLS startup·readiness, 초기 사용자 생성, 런타임 DML, 마이그레이션 DDL과 재시작이
성공해야 한다. 하나라도 확인하지 못하면 `deploy/k8s/bootstrap/namespace.yaml`의
`pod-security.kubernetes.io/enforce`를 `baseline`으로 유지한다. 검증 증거를 승인한 뒤 해당 값을
`restricted`로 변경하고 먼저 서버 측 dry-run과 diff를 확인한 후 적용한다.

```bash
kubectl apply --server-side --dry-run=server -k deploy/k8s/bootstrap
kubectl diff -k deploy/k8s/bootstrap
kubectl apply -k deploy/k8s/bootstrap
kubectl -n baton-go exec pod/baton-go-mysql-0 -- sh -ec \
  "id -u; id -g; awk '/^CapEff:/ {print \$2}' /proc/1/status"
```

마지막 명령은 차례로 `999`, `999`, `0000000000000000`을 출력해야 한다. 이 확인은 PVC
복원·재시작과 데이터베이스 기능 검증을 대신하지 않는다.

비밀값 관리자 또는 External Secrets controller를 사용한다면 다음 이름과 키로 구체화한다.

```text
baton-go-link-code-secret
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

- HMAC 비밀값은 32자 이상의 새 무작위 값이며 base64url 또는 16진수처럼 파서에 안전한
  알파벳으로 생성한다.
- 런타임 DB 사용자는 변경 불가 ConfigMap의 `baton_go`, 마이그레이션 사용자는
  `baton_go_migrator`다. BATON 계정이나 `root`를 사용하지 않는다. 사용자 이름 변경은 기존 PVC에
  자동 반영되지 않으므로 일반 설정 변경으로 수행하지 않는다.
- 런타임, 마이그레이션과 root 비밀번호는 모두 서로 다른 32자 이상의 패딩 없는 base64url 또는
  16진수 값으로 생성한다. 신규 DB 런타임 사용자 초기화 스크립트는 안전한 SQL 리터럴을 위해 이
  알파벳을 강제한다. 따옴표, 역슬래시, 공백, 제어 문자와 줄바꿈을 사용하지 않는다.
- 서버 `tls.crt`의 DNS SAN에는 JDBC host `baton-go-mysql`이 있어야 한다. `tls.key`는 MySQL이
  무인 기동 중 읽을 수 있는 암호화되지 않은 PEM으로 발급하되 저장소, 작업 로그와 백업
  메타데이터에는 복사하지 않고 Secret 암호화·RBAC로 보호한다. `ca.pem`은 해당 서버
  인증서를 검증하는 CA 체인이다.
- `truststore.p12`에는 공개 서버 CA 인증서만 넣고 개인 키, 클라이언트 인증서나 다른
  자격 증명을 넣지 않는다. PKCS12 저장소 비밀번호는 자격 증명이 아니라 공개 CA 컨테이너의
  호환값인 고정 문자열 `baton-go-public-ca-v1`을 사용한다. 애플리케이션과 마이그레이션 Job은
  이 값을 Hikari 드라이버 속성으로, 별도 guard CLI는 JDBC `Properties`로 제공한다.
  Hikari DEBUG가 임의 드라이버 속성을 출력할 수 있으므로
  이 값을 비밀값으로 바꾸거나 다른 자격 증명을 같은 속성에 넣지 않는다. 신뢰 저장소 객체의
  변경 무결성과 접근 제한은 Secret 저장 시 암호화와 RBAC로 보장한다.

런타임 사용자 초기화 스크립트는 런타임 사용자 이름과 비밀번호의 안전한 SQL 알파벳을 확인한 뒤
로컬 root socket으로 DML 전용 권한을 만든다. 마이그레이션·root 비밀번호도 같은 파서 안전
알파벳으로 생성한다. 인증서/키 쌍과 SAN은 발급 PKI에서 검증하고, 실제 배포에서는
mysqld startup·TLS readiness와 Connector/J `VERIFY_IDENTITY`가 잘못된 값을 거부한다.

```text
jdbc:mysql://baton-go-mysql:3306/baton_go?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/etc/baton-go/mysql-tls/truststore.p12&trustCertificateKeyStoreType=PKCS12&fallbackToSystemTrustStore=false&serverTimezone=UTC
```

`useSSL=false`, `sslMode=DISABLED|PREFERRED`, `allowPublicKeyRetrieval=true`, 다른 host와
`fallbackToSystemTrustStore=true` 및 `trustCertificateKeyStorePassword`를 운영
`BATON_GO_DB_URL`에 넣지 않는다. 애플리케이션과 마이그레이션 Job은 같은 URL·공개 CA 신뢰 저장소를
사용하되 서로 다른 데이터베이스 사용자 이름/비밀번호를 사용한다.

### DB 대기 시간

별도 실행기나 재시도 코드를 두지 않고 Hikari와 Connector/J의 표준 설정을 사용한다.
아래 값의 단위는 밀리초다.

| 설정 | 일반 애플리케이션 | 마이그레이션 전용 실행 | 제한 대상 |
| --- | ---: | ---: | --- |
| `spring.datasource.hikari.connection-timeout` | 3000 | 3000 | 풀에서 연결을 확보하는 대기 |
| `spring.datasource.hikari.validation-timeout` | 1000 | 1000 | 풀의 연결 유효성 검사 |
| `spring.datasource.hikari.data-source-properties.connectTimeout` | 3000 | 3000 | Connector/J 소켓 연결 |
| `spring.datasource.hikari.data-source-properties.socketTimeout` | 5000 | 600000 | Connector/J 소켓 읽기 대기 |

`--baton-go.migration-only=true` 실행기는 `migration` 프로필을 자동으로 추가한다.
일반 애플리케이션에는 이 프로필을 활성화하지 않는다. 로컬 일반 기동에 포함된 Flyway는
일반 애플리케이션 값을 사용하므로, 긴 DDL은 마이그레이션 전용 실행으로 분리한다.

이 값은 HTTP 요청이나 SQL 전체 실행 시간의 상한이 아니다. DNS 조회, 여러 SQL 실행,
연결 재시도와 응답 처리 시간을 합산한 제한으로 해석하지 않는다. 운영 트래픽과 DDL 예상 시간을
측정해 표준 Spring 설정으로 조정한다. 예를 들어 Job의 명령 인자에
`--spring.datasource.hikari.data-source-properties.socketTimeout=900000`을 추가하면
마이그레이션 소켓 읽기 대기만 15분으로 바뀐다. Job 전체 제한인
`activeDeadlineSeconds: 1800`과도 함께 검토한다.

공유 `BATON_GO_DB_URL`에 `connectTimeout`·`socketTimeout`을 중복 지정하지 않는다.
특히 URL에 짧은 `socketTimeout`을 넣어 런타임과 마이그레이션의 분리를 무효화하지 않는다.
별도 JDBC CLI인 guard 도구는 Spring 설정을 읽지 않고 JDBC `Properties`에
`connectTimeout=3000`·`socketTimeout=5000`을 직접 지정한다. CLI 전체 실행 시간의 상한은 아니다.

통신 시간 초과만으로 쓰기 실패나 DDL 롤백을 단정하지 않는다. 링크 생성 재시도는 같은
`Idempotency-Key`를 사용하고, 마이그레이션 실패는 [실패 복구 절차](#마이그레이션-job-실패-복구)에
따라 실제 스키마와 Flyway 이력을 먼저 확인한다.
guard CLI의 결과가 불명확하면 [최초 결합 실행서](link-code-key-guard-binding.md#도구-빌드와-실행)에
따라 같은 비밀값 버전과 검증용 키로 다시 실행한다.
설정 의미는 [HikariCP 설정](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)과
[Connector/J 네트워크 설정](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-networking.html)을 따른다.

### Secret 생성

Kubernetes Secret의 RBAC는 객체 키 단위가 아니다. 따라서 URL, DB 런타임, 마이그레이션, root와
HMAC 비밀값을 서로 다른 객체로 유지한다. 운영자·컨트롤러 RBAC도 가능하면
`resourceNames`로 필요한 Secret만 허용하며 수명주기가 다른 Secret을 하나로 합치지 않는다.
Secret 읽기 권한이 없어도 Pod나 워크로드 템플릿을
만들거나 바꿀 수 있으면 해당 Secret을 마운트해 읽을 수 있으므로 워크로드 변경과
`exec/attach/ephemeralcontainers` 권한도 같은 감사 범위에 포함한다.

외부 Secret controller가 없다면 저장소 밖의 접근 제한된 파일을 사용할 수 있다.
파일에는 위 키를 `KEY=VALUE` 형식으로 넣고 권한을 `0600`으로 제한한다. 값을 명령 인자,
셸 기록, 티켓 또는 CI 로그에 넣지 않는다. `kubectl`은 파일을 데이터로 읽으며 `source`로
실행하지 않는다.

```bash
kubectl -n baton-go create secret generic baton-go-link-code-secret \
  --from-env-file=/secure/path/baton-go-link-code-secret.env \
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

비공개 레지스트리 자격 증명도 전용 Docker 설정 파일이나 외부 Secret controller로 만든다.
다른 레지스트리 자격 증명이 함께 든 개인 Docker 설정 전체를 복사하지 않는다.

```bash
kubectl -n baton-go create secret generic baton-go-registry \
  --type=kubernetes.io/dockerconfigjson \
  --from-file=.dockerconfigjson=/secure/path/baton-go-registry-config.json \
  --dry-run=client -o yaml | kubectl apply -f -
```

다음 출력은 키 이름과 바이트 수만 보여 주며 실제 값은 출력하지 않는다.

```bash
kubectl -n baton-go describe secret baton-go-link-code-secret
kubectl -n baton-go describe secret baton-go-database-client-config
kubectl -n baton-go describe secret baton-go-database-runtime-credentials
kubectl -n baton-go describe secret baton-go-database-migration-credentials
kubectl -n baton-go describe secret baton-go-database-bootstrap-credentials
kubectl -n baton-go describe secret baton-go-mysql-server-tls
kubectl -n baton-go describe secret baton-go-mysql-client-tls
```

외부 Secret controller를 쓸 때도 키 이름과 파일 형식은 같아야 한다. 서버 TLS Secret은
MySQL Pod에만, 클라이언트 신뢰 저장소는 애플리케이션과 마이그레이션 Job에만 마운트된다. 실제 Secret
값을 렌더 검증을 위해 임시 매니페스트에 넣지 않는다.

기존 `baton-go-runtime-credentials` 또는 `baton-go-management-credentials`를 사용하는 환경은
새 JWT 발급자와 호출자 scope 검증을 먼저 완료한다. `baton-go-link-code-secret`은 기존 DB와
결합한 비밀값 관리자 버전으로 별도 생성하고 `kubectl get secret -o yaml`로 값을 출력하지 않는다.
새 Pod가 JWT 설정과 분리된 HMAC Secret으로 Ready가 되고 모든 호출자가 JWT로 전환된 뒤 다른
워크로드가 이전 Secret을 참조하지 않는지 확인한 후 기존 관리 토큰 Secret을 폐기한다.

## 4. 최초 배포

변경 내용을 검토한 뒤 선언적으로 적용한다.

```bash
kubectl diff -k deploy/k8s/overlays/private-server
kubectl apply -k deploy/k8s/overlays/private-server
kubectl -n baton-go rollout status statefulset/baton-go-mysql --timeout=10m
kubectl -n baton-go patch job baton-go-database-migration \
  --type=merge --patch '{"spec":{"suspend":false}}'
kubectl -n baton-go wait --for=condition=complete \
  job/baton-go-database-migration --timeout=10m
kubectl -n baton-go rollout status deployment/baton-go --timeout=10m
```

Kubernetes는 애플리케이션, 마이그레이션 Job과 MySQL의 생성 순서를 보장하지 않는다. 마이그레이션
Job은 `suspend=true`, `backoffLimit=0`, `restartPolicy=Never`로 생성한다. MySQL StatefulSet의
준비 상태를 확인한 운영자가 Job을 한 번만 시작하며 DB 연결 실패와 Flyway DDL 실패를 같은 자동
재시도로 처리하지 않는다. 애플리케이션은 마이그레이션 전 스키마 검증에 실패하면 Pod 재시작
정책으로 재시도하고 준비 상태가 성공하기 전에는 Service 엔드포인트가 되지 않는다. Job이
`Complete`가 되지 않으면 애플리케이션 배포를 성공으로 판단하지 말고 Job Pod의 종료 원인과
MySQL TLS·자격 증명을 먼저 확인한다. DDL이 시작된 가능성이
있거나 실패 위치가 불명확하면 Job을 바로 삭제·재생성하지 않고
[7절의 마이그레이션 Job 실패 복구](#마이그레이션-job-실패-복구)를 따른다.

마이그레이션 Job의 구조와 안전 차단 정책은
[ADR-0008](../ADR/0008_private-kubernetes-database-topology/adr.md)을 따른다.

MySQL 기동 실패는 주 컨테이너 로그에서 확인한다. 출력에는 자격 증명 원문, 인증서나
JDBC URL을 복사하지 않는다. 공식 진입점이 데이터 디렉터리를 건드린 뒤 실패했을 수 있으므로
Pod 재시작만으로 안전하게 초기화가 반복된다고 가정하지 않고 아래 부분 초기화 절차를 따른다.

```bash
kubectl -n baton-go logs pod/baton-go-mysql-0 -c mysql
```

신규 빈 PVC에서 MySQL 공식 이미지는 다음을 최초 한 번만 수행한다.

- `baton_go` 데이터베이스와 DDL/DML 권한의 `baton_go_migrator` 사용자 생성
- 저장소 초기화 스크립트로 DML 전용 `baton_go` 런타임 사용자 생성
- root 비밀번호 설정
- `MYSQL_ROOT_HOST=localhost`에 따라 원격 root 사용자 생성 생략
- 외부 서버 인증서로 TLS를 시작하고 TCP 평문 연결 거부

초기화 환경 변수는 이미 데이터베이스가 든 PVC의 계정이나 비밀번호를 갱신하지 않는다. 기존
PVC에는 사용자 분리 초기화 스크립트도 다시 실행되지 않는다. 이 구조를 기존 PVC에 도입하려면 먼저
승인된 MySQL 관리 채널에서 마이그레이션 사용자 생성·권한 부여와 런타임 사용자의 DDL 권한 회수를
완료한 뒤 Secret과 워크로드를 전환한다. DB Secret만 먼저 바꾸지 않는다. 이 저장소의 첫
비공개 서버 배포는 신규 빈 DB를 전제로 한다.

일회성 Job이 Flyway 스키마를 만든 뒤 장기 실행 애플리케이션은 DML 사용자로 JPA 스키마를
검증한다. 신규 빈 DB의 링크 코드 HMAC 보호 장치는 애플리케이션 시작 시 현재 HMAC 비밀값에 자동
결합된다. 이후에는 DB와 `BATON_GO_LINK_CODE_SECRET`을 항상 같은 시점의 복구 단위로
보존한다. 버전별 키 묶음을 도입하기 전에는 HMAC 비밀값만 회전하지 않는다.

### 부분 초기화 실패 복구

MySQL 공식 진입점이 시스템 스키마를 만들고 계정·초기화 스크립트 단계에서 실패하면
`/var/lib/mysql/mysql`이 남을 수 있다. 공식 이미지는 재시작 때 이를 기존
데이터베이스로 판단하므로 초기화 환경 변수와 `/docker-entrypoint-initdb.d`를 다시 실행하지 않는다.
Pod 재시작이나 Secret 수정만으로 런타임·마이그레이션 계정이 복구된다고 가정하지 않는다.

PVC 삭제·재생성은 다음 증거를 모두 확인하고 서비스·DB 운영자가 승인한 **신규 빈 DB의 첫
배포**에만 허용한다.

1. PVC가 이번 최초 배포에서 새로 할당됐고 스냅샷·복원이나 기존 볼륨 재결합 이력이 없다.
2. 마이그레이션 Job이 한 번도 `Complete`가 아니었고 애플리케이션이 한 번도 `Ready`가 아니었으며
   관리 쓰기 경로와 호출자 아웃박스가 열리지 않았다.
3. MySQL 로그와 배포 기록상 업무 행이나 보존해야 할 스키마가 생성될 가능성이 없음을 확인했다.

확인된 빈 PVC만 대상으로 애플리케이션 Deployment와 마이그레이션 Job을 먼저 중지한다.
애플리케이션 Pod는 0개, 마이그레이션 Job은 `suspend=true`·활성 작업 0이고 Pending·Running Pod가
0개임을 확인한 뒤 StatefulSet을 0으로 내린다. Failed·Succeeded Job Pod는 활성 쓰기 프로세스가
아니므로 삭제를 기다리지 않고 복구 증거로 보존한다. 호출자 아웃박스·관리 쓰기 경로 차단도
별도로 유지한다. 현재 클러스터 컨텍스트, PVC UID·PV·StorageClass와 PV의 실제 회수 정책을
승인 기록과 대조한다. 아래 PVC 삭제는 볼륨을 복구 불가능하게 제거할 수 있는 파괴
작업이므로 승인 기록 없이 실행하지 않는다.

```bash
kubectl config current-context
kubectl -n baton-go get deployment/baton-go job/baton-go-database-migration \
  statefulset/baton-go-mysql pvc/data-baton-go-mysql-0 -o wide
kubectl -n baton-go scale deployment/baton-go --replicas=0
kubectl -n baton-go patch job baton-go-database-migration \
  --type=merge --patch '{"spec":{"suspend":true}}'
kubectl -n baton-go wait --for=delete \
  pod -l app.kubernetes.io/name=baton-go,app.kubernetes.io/component=application \
  --timeout=10m
kubectl -n baton-go get job baton-go-database-migration \
  -o custom-columns=NAME:.metadata.name,SUSPEND:.spec.suspend,ACTIVE:.status.active
kubectl -n baton-go get pod \
  -l app.kubernetes.io/name=baton-go,app.kubernetes.io/component=database-migration \
  --field-selector=status.phase!=Succeeded,status.phase!=Failed \
  -o custom-columns=NAME:.metadata.name,PHASE:.status.phase,DELETION:.metadata.deletionTimestamp
```

Job 출력이 `SUSPEND=true`, `ACTIVE=<none>` 또는 `0`이고 바로 다음 Pod 조회가 행을 반환하지
않는지 확인한다. Pending·Running·Terminating Pod가 하나라도 보이면 DB 중지로 진행하지
않는다. Failed·Succeeded Pod와 Job 로그는 비밀값을 제외한 실패 증거로 보존한다.

```bash
kubectl -n baton-go scale statefulset/baton-go-mysql --replicas=0
kubectl -n baton-go wait --for=delete pod/baton-go-mysql-0 --timeout=10m
kubectl -n baton-go get pvc data-baton-go-mysql-0 \
  -o custom-columns=NAME:.metadata.name,UID:.metadata.uid,PV:.spec.volumeName,SC:.spec.storageClassName
BATON_GO_RECOVERY_PV_NAME="$(kubectl -n baton-go get pvc data-baton-go-mysql-0 \
  -o jsonpath='{.spec.volumeName}')"
BATON_GO_RECOVERY_STORAGE_CLASS_NAME="$(kubectl -n baton-go get pvc data-baton-go-mysql-0 \
  -o jsonpath='{.spec.storageClassName}')"
test -n "${BATON_GO_RECOVERY_PV_NAME}"
test -n "${BATON_GO_RECOVERY_STORAGE_CLASS_NAME}"
kubectl get pv "${BATON_GO_RECOVERY_PV_NAME}" \
  -o custom-columns=NAME:.metadata.name,SC:.spec.storageClassName,RECLAIM:.spec.persistentVolumeReclaimPolicy
kubectl get storageclass "${BATON_GO_RECOVERY_STORAGE_CLASS_NAME}"
```

PVC UID·PV·StorageClass·회수 정책이 승인 기록과 정확히 일치할 때만 다음 파괴 블록을
별도로 실행한다.

```bash
kubectl -n baton-go delete pvc data-baton-go-mysql-0 --wait=true
kubectl -n baton-go scale statefulset/baton-go-mysql --replicas=1
kubectl -n baton-go rollout status statefulset/baton-go-mysql --timeout=10m
```

Job이 이미 `Failed` 종료 조건이면 suspend 해제로 재시도할 수 없다. 실패 원인과
비밀값이 아닌 메타데이터를 보존한 뒤 Job만 삭제하고 비공개 오버레이에서
`app.kubernetes.io/component=database-migration` label과 일치하는 Job만 재적용한다. 전체
오버레이를 적용하면 애플리케이션 복제본이 1로 돌아갈 수 있으므로 이 복구 분기에서는 금지한다.

```bash
kubectl -n baton-go get job baton-go-database-migration
kubectl -n baton-go delete job baton-go-database-migration --wait=true
kubectl apply -k deploy/k8s/overlays/private-server \
  --selector app.kubernetes.io/component=database-migration
kubectl -n baton-go patch job baton-go-database-migration \
  --type=merge --patch '{"spec":{"suspend":false}}'
kubectl -n baton-go wait --for=condition=complete \
  job/baton-go-database-migration --timeout=10m
kubectl -n baton-go scale deployment/baton-go --replicas=1
kubectl -n baton-go rollout status deployment/baton-go --timeout=10m
```

종료 상태가 아닌 중지된 Job만 다음처럼 재개한다.

```bash
kubectl -n baton-go patch job baton-go-database-migration \
  --type=merge --patch '{"spec":{"suspend":false}}'
kubectl -n baton-go wait --for=condition=complete \
  job/baton-go-database-migration --timeout=10m
kubectl -n baton-go scale deployment/baton-go --replicas=1
kubectl -n baton-go rollout status deployment/baton-go --timeout=10m
```

어느 분기에서도 관리 외부 경계·호출자 쓰기 경로 차단과 애플리케이션 복제본 0은 마이그레이션
Job이 `Complete`가 될 때까지 유지한다. 종료된 Job 재생성 분기에서도 마지막 두 애플리케이션
규모 조정·배포 명령만 마이그레이션 완료 뒤 실행한다.

세 조건 중 하나라도 확인할 수 없거나 데이터가 존재할 가능성이 있으면 PVC를 삭제하지 않는다.
애플리케이션·마이그레이션 쓰기 경로를 차단하고 먼저 일관된 스냅샷 또는 MySQL 인식 백업을 확보한
뒤 승인된 로컬 MySQL 관리 채널에서 계정과 스키마 상태를 조사한다. 실제 비밀번호는 SQL
명령 인자, 셸 기록이나 작업 로그에 넣지 않고 보호된 대화형 입력 또는 승인된 자격 증명
주입으로 전달한다.

- `baton_go` 런타임 계정에는 `baton_go.*`의 `SELECT`, `INSERT`, `UPDATE`, `DELETE`만
  부여되고 DDL·`GRANT` 권한이 없는지 확인·복구한다.
- `baton_go_migrator` 계정과 Flyway 스키마 이력을 확인하고 계정의 실제 비밀번호를 먼저
  복구한 뒤 대응하는 Secret 버전을 맞춘다. Secret만 먼저 바꾸지 않는다.
- 로컬 root 계정 접근을 복구할 수 없거나 시스템 스키마 손상 가능성이 있으면 임의 초기화 스크립트
  재실행이나 데이터 디렉터리 파일 삭제를 하지 않고 DBA와 백업/복원 담당자에게 상향 보고한다.
- 계정 수동 복구 뒤 마이그레이션 Job `Complete`, 런타임 사용자의 DML 성공·DDL 거부, 애플리케이션
  준비 상태를 차례로 확인한다.

## 5. 접근 경계

기본본은 `Service/baton-go-http:8080`만 만든다. 이 포트에는 서로 다른 두 경로가 함께 있다.

- 공개: `GET·HEAD /l/{code}`
- 비공개 관리: `/api/v1` Prefix 경로 집합

Ingress 컨트롤러, 호스트 이름, TLS 발급 방식과 요청 제한 제품이 정해지지 않았으므로
저장소는 Ingress를 만들지 않는다. 환경별 외부 경계는 다음을 강제해야 한다.

아래 `/l/**`, `/api/v1/**` 표기는 경로 집합을 뜻하며 Ingress 매니페스트의 와일드카드 문법이
아니다. 표준 Kubernetes Ingress에서는 각각 `path: /l`, `path: /api/v1`과
`pathType: Prefix`를 사용하고 선택한 controller의 실제 일치 동작을 사전 검증한다.

애플리케이션 수신 NetworkPolicy는 기본적으로 모든 Pod 수신을 격리한다. `8080` 호출이
필요한 Ingress 컨트롤러 또는 신뢰된 서비스 호출자의 Namespace에 다음 label을 명시적으로
부여한다. 이 label은 Namespace 안의 모든 Pod에 `8080` 네트워크 도달성을 주므로 서로
신뢰하지 않는 워크로드와 같은 Namespace에 두지 않는다.

```bash
kubectl label namespace <approved-edge-or-caller-namespace> \
  baton-go.networking/allow-http=true
```

1. 공개 HTTPS ingress는 `path: /l`, `pathType: Prefix`만 HTTP Service로 전달하고
   `/api/v1` Prefix를 노출하지 않는다.
2. 비공개 관리 ingress 또는 서비스 간 경로만 `path: /api/v1`,
   `pathType: Prefix`를 전달한다.
3. 공개 외부 경계는 분산 요청 제한을 적용하고 접근 로그에서 `/l/{code}`의 코드를
   마스킹한다.
4. 관리 JWT, Authorization, `Idempotency-Key`, 대상 경로와 전체 단축 URL을
   외부 경계·APM 로그에 기록하지 않는다.
5. Actuator `8081`은 공개/비공개 Ingress에 연결하지 않는다.

`8081`은 위 HTTP label로 열리지 않는다. 모니터링 Pod에서 직접 수집해야 한다면 모니터링
Namespace와 실제 수집기 Pod 템플릿에 각각 다음 label을 부여해야 한다. 두 selector는 AND
조건이다. Actuator Service는 기본 생성하지 않으므로 환경별 Pod 탐색도 별도 구성한다.

```text
monitoring namespace: baton-go.networking/allow-actuator=true
scraper Pod:          baton-go.networking/actuator-client=true
```

Kubernetes NetworkPolicy 모델에서는 일반적으로 Pod가 실행 중인 노드에서 오는 kubelet probe
트래픽이 허용되지만, CNI 호스트 방화벽·엄격한 정책과 host-network 구현은 다를 수 있다.
최초 배포 전에 startup/liveness/readiness probe가 실제 CNI에서 통과하는지 확인한다. 차단되면
승인된 노드 CIDR 또는 CNI 전용 호스트 엔드포인트 정책만 좁게 추가하고 `8081`을
`0.0.0.0/0`이나 전체 클러스터 Namespace에 열지 않는다. host-network ingress가 노드 트래픽으로
보여 namespaceSelector를 우회하는지도 별도로 검증한다.

Actuator Service를 기본 생성하지 않아 kubelet probe 외의 안정된 클러스터 엔드포인트가 없다.
일시적인 운영 확인은 CNI에서 허용되는 로컬 포트 전달로 수행하고 종료한다. 엄격한 CNI가
포트 전달도 차단하면 위 label을 가진 일회성 운영 Pod를 사용한다.

```bash
kubectl -n baton-go port-forward deployment/baton-go 18081:8081
curl --fail --silent http://127.0.0.1:18081/actuator/health/readiness
```

MySQL NetworkPolicy는 같은 Namespace의 BATON GO 애플리케이션과 database-migration label에서
오는 TCP 3306만 허용한다. MySQL 자체도 `require_secure_transport=ON`이며 애플리케이션과 Job은
`VERIFY_IDENTITY`로 서버 DNS를 검증한다. NetworkPolicy를 집행하지 않는 CNI에서는 selector
차단 효과가 없지만 TLS를 비활성화해 보완하지 않는다.

현재 기본 NetworkPolicy에는 `policyTypes: Egress`가 없으므로 선택된 Pod의 외부 연결은
차단되지 않는다. 공개 운영 전에 환경별 overlay 또는 CNI 정책으로 다음 최소 흐름을
표현하고, 허용 흐름과 임의 외부 주소 차단을 모두 실제 Pod에서 검증한다.

- 애플리케이션: 클러스터 DNS, `baton-go-mysql:3306`과 승인한 관리 JWT 발급자의
  JWK Set HTTPS 주소
- 마이그레이션 Job: 클러스터 DNS와 `baton-go-mysql:3306`
- MySQL: 필수 egress가 없음을 확인하되, 환경이 외부 복제·백업을 사용하면 승인된
  목적지와 포트만 별도 허용
- 이미지 pull·노드 DNS·kubelet probe: Pod egress와 노드 흐름을 구분해 CNI별로 검증

DNS Namespace·Pod label, Service IP와 host-network 처리는 클러스터마다 다르므로 저장소
기본본에 예시 selector를 고정하지 않는다. DNS 증거 없이 egress 기본 차단만 적용해
시작·준비 탐지를 깨뜨리지 않는다.

### 수집과 경보 관문

`/actuator/prometheus`와 health endpoint가 켜져 있다는 사실만으로는 운영 감시가 완료되지
않는다. 공개 운영 전에 실제 수집기의 target이 `UP`인지 확인하고, 임계치·지속 시간·
알림 경로·응답 담당자를 명시한 최소 경보를 연결한다.

저장소에는 5xx·429·저장 대상 계약 위반의
[Prometheus 경보 규칙과 검증 절차](prometheus-alerts.md)가 있다. 수집 label과 초기 임계치를
실제 환경에 맞춰 연결하며, 아래 인프라·백업 경보는 별도로 준비한다.

- `Job/baton-go-database-migration` 실패·시간 초과
- 애플리케이션·MySQL Pod `NotReady`, 재시작과 배포 상태 이상
- HTTP 5xx 오류율과 공개 해석 429 지속 증가
- DB를 포함한 readiness 실패
- `baton.go.public.resolver.target.contract.violations` 증가
- PVC 사용률·증가 추세·확장 실패
- 백업 실패와 정책에서 정한 시간 동안 성공 백업 부재

각 경보는 데이터를 조작하지 않는 방식으로 시험하고 규칙 버전, 발화 시각, 알림
수신·확인 결과와 담당자를 운영 증거에 남긴다. 대시보드 화면만 있거나 알림을
실제로 전송하지 않은 규칙은 관문 통과 증거가 아니다.

## 6. 배포 검증

다음 상태를 확인한다.

```bash
kubectl -n baton-go get deployment,statefulset,job,pod,service,pvc,networkpolicy
kubectl -n baton-go get events --sort-by=.lastTimestamp
```

- MySQL과 애플리케이션 Pod가 `Ready`다.
- MySQL startup·readiness probe가 런타임 계정의 로컬 TCP `ssl-mode=REQUIRED` 세션으로
  통과했다. socket ping만으로 서버 TLS 성공을 추정하지 않는다.
- 데이터베이스 마이그레이션 Job이 `Complete`이며 실패한 이전 Pod 원인이 남아 있지 않다.
- `data-baton-go-mysql-0` PVC가 `Bound`다.
- HTTP Service만 존재하며 MySQL Service는 헤드리스다.
- 애플리케이션 컨테이너의 Secret 참조에는 마이그레이션·root 비밀번호가 없고 마이그레이션 Job에는
  런타임·root 비밀번호가 없다.
- MySQL은 `require_secure_transport=ON`이고 승인된 세션의 `Ssl_cipher`가 비어 있지 않다.
  별도 격리 검증에서는 잘못된 CA 또는 JDBC host로 `VERIFY_IDENTITY` 연결이 실패한다. 인증서와
  JDBC URL 전체는 증거에 기록하지 않는다.
- 승인하지 않은 Namespace에서 애플리케이션 `8080`, 일반 Pod에서 Actuator `8081`, 애플리케이션과
  마이그레이션 label이 아닌 Pod에서 MySQL `3306` 연결이 실패한다.
- 평상시 운영 기능 두 설정값은 `false`다.

그 다음 비공개 관리 경로에서 새 정규 UUID 요청 의도 한 건으로 생성, 같은 요청 의도
재생, 공개 해석과 폐기를 검증한다. 실제 관리 JWT, `Idempotency-Key`, 공개 코드,
대상 경로와 전체 단축 URL을 셸 기록이나 검증 증거에 복사하지 않는다. 증거에는 HTTP
상태, 링크 ID, 요청 ID와 시각처럼 허용된 메타데이터만 남긴다.

## 7. 업데이트와 되돌리기

애플리케이션 이미지와 비밀값 제외 ConfigMap 변경은 Kustomize를 다시 적용한다. ConfigMap 이름에
내용 해시가 붙어 Pod 템플릿이 바뀌므로 설정 변경도 새 배포를 만든다. 마이그레이션 Job의
Pod 템플릿은 변경 불가이고 같은 배포 이미지를 사용한다. 이전 Job이 `Complete`이면
필요한 비밀값 제외 메타데이터를 보존하고 Job만 삭제한다. `Failed`이거나 성공 여부가
불명확하면 아래 실패 복구 절차 전에 Job을 삭제·재생성하지 않는다.
마이그레이션 Job에는 고정 TTL을 두지 않는다. 성공 Job은 완료 증거를 보존한 뒤 아래 절차로
삭제하고, 실패 Job과 Pod는 복구 분류와 증거 보존이 끝날 때까지 유지한다.

업데이트를 시작할 때 이전 Job 상태는 다음 세 가지로 분류한다.

- Job이 있고 `Complete=True`이면 완료 메타데이터를 보존한 뒤 Job만 삭제한다.
- Job이 없으면 부재 자체를 성공 증거로 사용하지 않는다. 직전
  릴리스의 Job 완료 증거와 이미지 다이제스트·Flyway 스키마 버전을 확인하고, 승인된 읽기 전용
  DB 관리 채널에서 현재 `flyway_schema_history`의 마지막 성공 버전이 그 증거와 일치할 때만
  다음 적용으로 진행한다. 어느 증거든 없거나 일치하지 않으면 아래 실패 복구 절차로 전환한다.
- Job이 `Active`, `Failed`이거나 상태를 판정할 수 없으면 삭제·재생성하지 않고 아래 실패
  복구 절차로 전환한다.

```bash
set -eu

migration_job="$(kubectl -n baton-go get job baton-go-database-migration \
  --ignore-not-found -o name)"
if test -n "$migration_job"; then
  test "$(kubectl -n baton-go get job baton-go-database-migration \
    -o jsonpath='{.status.conditions[?(@.type=="Complete")].status}')" = "True" || {
    echo "마이그레이션 Job이 Complete가 아니므로 삭제하지 않습니다." >&2
    exit 1
  }
  kubectl -n baton-go get job baton-go-database-migration \
    -o 'custom-columns=NAME:.metadata.name,IMAGE:.spec.template.spec.containers[0].image,COMPLETED:.status.completionTime'
  kubectl -n baton-go delete job baton-go-database-migration --wait=true
else
  echo "마이그레이션 Job이 없습니다. 직전 완료 증거와 현재 Flyway 성공 버전을 대조한 뒤 진행하십시오." >&2
  exit 2
fi
```

`Complete` Job을 위 명령으로 삭제했거나, Job 부재 분기에서 직전 완료 증거와 현재 Flyway
성공 버전의 일치를 확인한 뒤에만 다음 적용 명령을 실행한다.

```bash
set -eu

kubectl diff -k deploy/k8s/overlays/private-server
kubectl apply -k deploy/k8s/overlays/private-server
kubectl -n baton-go rollout status statefulset/baton-go-mysql --timeout=10m
kubectl -n baton-go patch job baton-go-database-migration \
  --type=merge --patch '{"spec":{"suspend":false}}'
kubectl -n baton-go wait --for=condition=complete \
  job/baton-go-database-migration --timeout=10m
kubectl -n baton-go rollout status deployment/baton-go --timeout=10m
```

위 삭제는 `Complete`인 마이그레이션 Job 객체만 대상으로 하며 StatefulSet, PVC, Namespace와
Secret에는 사용하지 않는다. Job이 아직 실행 중이면 중단하지 말고 원인을 확인한다. 한 번의
Kustomize 적용은 Job과 Deployment 순서를 보장하지 않으므로 모든 Flyway 변경은 구 애플리케이션과
구 스키마에 호환되는 확장 단계여야 한다. Job `Complete` 확인 뒤 새 애플리케이션을 검증하고,
열/테이블 제거 같은 축소 단계는 모든 구 Pod와 읽기 프로세스가 사라진 후 별도 배포에서
수행한다. 애플리케이션이 마이그레이션보다 먼저 시작해 스키마 검증에 실패하는 짧은 구간은
재시작으로 복구되지만, 이를 파괴적 마이그레이션 허용 근거로 사용하지 않는다.

### 마이그레이션 Job 실패 복구

MySQL 8.4의 InnoDB `ALTER TABLE`은 **각 DDL 문장 단위**로 전체 적용 또는 롤백되는
원자적 DDL이다. 하지만 여러 DDL이 든 Flyway SQL 파일 전체가 하나의 트랜잭션은 아니다.
V4는 `smart_links`의 시각 열을 바꾸는 첫 `ALTER TABLE`과
`link_creation_requests.created_at`을 바꾸는 둘째 `ALTER TABLE`로 구성되므로 첫 문장만
커밋된 혼합 상태가 가능하다. V5는 `public_origin` 열을 추가하는 한 문장이지만,
열 추가 커밋 후 Flyway 성공 이력 기록 전에 프로세스가 종료될 수 있다.

Job이 `Failed`이거나 결과가 불명확하면 다음 순서를 지킨다.

1. 비공개 `/api/v1` 경계와 BATON 호출자 아웃박스 전송을 차단하고 처리 중인
   쓰기가 없음을 확인한다. 애플리케이션을 복제본 `0`으로 축소하고 스키마 분류가
   끝날 때까지 공개 `/l`도 계획 중단 상태로 유지한다. Job Pod가 아직 DDL을
   실행 중이면 강제 삭제하지 않고 현재 시도의 종료 상태를 확인한다.

   ```bash
   kubectl -n baton-go scale deployment/baton-go --replicas=0
   kubectl -n baton-go wait --for=delete \
     pod -l app.kubernetes.io/name=baton-go,app.kubernetes.io/component=application \
     --timeout=10m
   kubectl -n baton-go get job baton-go-database-migration \
     -o 'custom-columns=NAME:.metadata.name,SUCCEEDED:.status.succeeded,FAILED:.status.failed,ACTIVE:.status.active'
   ```

2. 남아 있는 Job과 Pod에서 접근이 제한된 사고 저장소로 비밀값 제외 증거를 보존한다. 아래
   변수는 새 빈 디렉터리로 지정하고,
   저장 후 로그에 JDBC URL, 인증서 경로나 자격 증명 원문이 없는지 제한된
   환경에서 검토한다. 검토 전 파일을 티켓·채팅·CI 산출물로 복사하지 않는다.

   ```bash
   if test -z "${BATON_GO_MIGRATION_EVIDENCE_DIR:-}" \
     || ! test -d "${BATON_GO_MIGRATION_EVIDENCE_DIR}" \
     || test -n "$(find "${BATON_GO_MIGRATION_EVIDENCE_DIR}" \
       -mindepth 1 -maxdepth 1 -print -quit)"; then
     echo "마이그레이션 증거 디렉터리는 비어 있는 기존 디렉터리여야 합니다." >&2
     exit 1
   fi
   (
     umask 077
     kubectl -n baton-go get job baton-go-database-migration -o yaml \
       >"${BATON_GO_MIGRATION_EVIDENCE_DIR}/job.yaml"
     kubectl -n baton-go get pod \
       -l app.kubernetes.io/name=baton-go,app.kubernetes.io/component=database-migration \
       -o yaml >"${BATON_GO_MIGRATION_EVIDENCE_DIR}/pods.yaml"
     kubectl -n baton-go logs \
       -l app.kubernetes.io/name=baton-go,app.kubernetes.io/component=database-migration \
       --all-containers=true --prefix=true --tail=-1 --max-log-requests=20 \
       >"${BATON_GO_MIGRATION_EVIDENCE_DIR}/pods.log"
     kubectl -n baton-go get events --sort-by=.lastTimestamp \
       >"${BATON_GO_MIGRATION_EVIDENCE_DIR}/events.txt"
   )
   ```

3. Job을 삭제하거나 다시 적용하기 전에 승인된 읽기 전용 DB 관리 채널에서
   `flyway_schema_history`와 실제 열 모양을 같은 시점에 대조한다. 조회 결과도 위 사고
   저장소에 보존하고 자격 증명은 SQL 인자·셸 기록에 넣지 않는다.

   ```sql
   SELECT installed_rank, version, description, script, checksum, installed_on, success
   FROM flyway_schema_history
   WHERE version IN ('4', '5')
   ORDER BY installed_rank;

   SELECT table_name, column_name, column_type, is_nullable,
          character_set_name, collation_name
   FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND (
       (table_name = 'smart_links'
        AND column_name IN ('not_before', 'expires_at', 'revoked_at', 'created_at'))
       OR (table_name = 'link_creation_requests'
           AND column_name IN ('created_at', 'public_origin'))
     )
   ORDER BY table_name, ordinal_position;
   ```

4. V4의 완료 모양은 `smart_links` 네 열과 `link_creation_requests.created_at`이
   모두 `datetime(6)`이고, `smart_links.created_at`과
   `link_creation_requests.created_at`만 `NOT NULL`인 상태다. V4 성공 이력이 없을 때는
   다음처럼 분기한다.

   - 모두 기존 `timestamp(6)`: DDL 적용 전 실패다. 원인을 제거하고 백업을 확보한
     뒤 변경하지 않은 V4 재실행을 검토한다.
   - `smart_links`만 완료 모양이고 `link_creation_requests.created_at`이
     `timestamp(6)`: 첫 `ALTER TABLE`만 커밋된 V4 혼합 상태다. 즉시 일관된 백업을
     확보하고 첫 `ALTER` 재실행의 잠금·시간 영향과 둘째 `ALTER` 완료를 격리
     환경에서 시험한 뒤 변경하지 않은 V4를 전진 재실행할지, 이전 일관된
     백업으로 복원할지 DBA와 결정한다.
   - 모두 완료 모양: DDL은 완료됐지만 성공 이력 기록 전에 종료됐을 수 있다.
     일관된 백업을 확보하고 동일 `MODIFY` 재실행의 잠금·시간 영향을 검증한 뒤
     변경하지 않은 V4 재실행 또는 이전 일관된 백업 복원을 선택한다.
   - 위 세 모양 외의 조합: 수동 변경이나 추가 drift로 분류하고 재실행하지 않는다.

5. V5 성공 이력이 없을 때 `public_origin`이 없으면 DDL 전 실패로 분류한다.
   열이 `varchar(255)`, `ascii`, `ascii_bin`, `NULL` 허용으로 존재하면 DDL 커밋과
   Flyway 이력 사이 실패로 분류하고 다음 조회로 쓰기 여부를 확인한다.

   ```sql
   SELECT COUNT(*) AS rows_with_public_origin
   FROM link_creation_requests
   WHERE public_origin IS NOT NULL;
   ```

   이 집계만으로는 실패 구간의 쓰기 부재를 증명할 수 없다. Job 시작 시각, 구 Pod
   종료, 외부 경계·아웃박스 차단 증거와 해당 구간 `created_at` 행 수를 함께 대조한다.
   V5 실행 전부터 쓰기 차단이 유지됐고 값이 한 건도 없음을 증명한 경우에만 일관된 백업 후
   격리된 복구 환경에서 Flyway 성공 이력 없는 열 제거·변경하지 않은 V5 재실행 절차를
   검증한다. 값이 있거나 쓰기 차단을 증명할 수 없으면 열을 삭제하지 않고 이전 일관된 백업
   복원 또는 데이터를 보존하는 별도 DBA 복구 계획을 사용한다. 열 모양이 다르면
   drift로 분류한다.

6. `flyway_schema_history.success=1`인데 실제 열 모양이 기대와 다르면 적용된
   마이그레이션을 수정하지 않고 백업 복원 또는 다음 버전 전진 마이그레이션으로
   교정한다. Flyway `repair`는 스키마를 복구하지 않는다. 실패 이력이 있고 실제
   스키마 모양, 백업·복구 선택과 재실행 절차를 확정한 경우에만 DBA와
   서비스 소유자가 해당 실패 이력 정리를 승인한다. 조사 전 무조건 `repair`를
   실행하거나 `flyway_schema_history`를 SQL로 수정·삭제·추가하지 않는다.

7. 확정한 복구를 격리 환경에서 먼저 재현한다. 실제 환경에서는 Job `Complete`,
   Flyway 이력과 실제 열 모양 일치, 애플리케이션 준비 상태, 새 생성·동일 요청
   재생·공개 해석·폐기를 순서대로 확인한 뒤에만 호출자 쓰기와 공개 경계를
   다시 연다.

단, V5의 `public_origin` 추가는 열 모양만 확장 호환이고 구 쓰기 프로세스와 동작까지
온라인 호환되지는 않는다. V5 적용 뒤 구 Pod가 생성한 예약은 `public_origin`이 `NULL`인 채
최초 요청을 성공시킬 수 있고 새 Pod는 그 요청 의도의 정확한 단축 URL을 재생할 수 없다. 이
비공개 서버의 신규 빈 DB 첫 배포에는 구 Pod가 없으므로 해당 경합이 없지만, 이미 애플리케이션이
실행 중인 환경에 V5를 도입할 때는 다음 유지 보수 순서를 사용한다.

1. 비공개 관리 외부 경계와 모든 BATON 호출자에서 링크 생성 쓰기 경로를 차단하고 처리 중
   요청과 아웃박스 전송을 비운다. 공개 해석 읽기는 비우는 동안과 구 Pod가 남아 있는
   동안에만 계속 운영할 수 있다.
2. `Deployment/baton-go`를 복제본 0으로 축소하고 구 Pod가 0개이며 생성 쓰기 경로가 남지 않았음을
   확인한다. 복제본 0 확인 시점부터 5단계에서 새 Deployment 준비 상태가 회복될 때까지
   공개 해석도 계획 중단 상태다.
3. V5 이전 예약의 최초 출처 목록을 조사한다. 증명할 수 있는 행만 정규 출처로
   유지 보수 중 채우고, 증거가 없는 행을 현재 설정으로 일괄 추정하지 않는다.
4. 완료된 이전 마이그레이션 Job을 위 절차로 삭제한 뒤 Kustomize를 적용한다. 새 애플리케이션이 먼저
   시작하더라도 외부 경계의 쓰기 경로 차단은 유지한다.
5. 마이그레이션 Job `Complete`, 새 Deployment 준비 상태, 신규 요청 의도의 최초 생성과 동일 URL 재생을
   순서대로 확인한 뒤 쓰기 경로를 다시 연다.

```bash
kubectl -n baton-go scale deployment/baton-go --replicas=0
kubectl -n baton-go wait --for=delete \
  pod -l app.kubernetes.io/name=baton-go,app.kubernetes.io/component=application \
  --timeout=10m
```

이 예외 절차를 일반 Flyway 변경의 쓰기 경로 중지 근거로 확대하지 않는다. 이후 마이그레이션은 다시
확장/축소 호환 규칙을 따른다.

Secret을 환경 변수로 주입한 실행 중 Pod는 Secret 객체가 바뀌어도 값을 자동으로
다시 읽지 않는다. JWT signing key와 Secret 회전은 다음 수명주기별 절차를 따른다.

- 관리 JWT signing key: 발급자가 새 공개키를 JWK Set에 먼저 게시하고 새 `kid`로 JWT 발급을
  전환한다. GO가 새 JWT를 검증하는지 확인한 뒤 이전 JWT 최대 수명과 JWK 캐시 관찰 시간이 모두
  지난 후에만 이전 공개키를 제거한다. JWK 조회는 성공했지만 해당 `kid`가 없으면 `401`,
  JWK 조회 자체가 실패하는 인증 서비스 장애는 `500 INTERNAL_ERROR`로 관리 요청을 거부해야 한다.
  장애 응답의 공통 오류 본문·`requestId`와 안전한 로그를 확인하고, 발급자의 가용성 및 Pod의
  JWK HTTPS 접근을 점검한다. 공개 `/l` 해석은 계속 동작하는지 함께 확인한다.
- DB 런타임 비밀번호: 백업과 유지 보수 시간을 확보하고 승인된 MySQL 관리 채널에서
  `baton_go` 계정을 새 비밀번호로 바꾼 뒤 런타임 Secret을 같은 값으로 갱신한다. 이어서
  `kubectl -n baton-go rollout restart statefulset/baton-go-mysql`과 배포 상태를 먼저
  완료하고, `kubectl -n baton-go rollout restart deployment/baton-go`와 준비 상태 검증을
  수행한다. probe도 DB 비밀번호를 사용하므로 StatefulSet 재시작을 생략하지 않는다.
- DB 마이그레이션 비밀번호: 실행 중인 Job이 없는 유지 보수 시간에 `baton_go_migrator`
  계정을 먼저 변경하고 마이그레이션 Secret을 갱신한다. 다음 마이그레이션 Job의 성공으로 새 값을
  검증한다. 일반 애플리케이션은 이 Secret을 참조하지 않는다.
- DB root 비밀번호: 로컬 root 계정을 승인된 관리 채널에서 먼저 변경하고 초기 설정
  Secret을 갱신한 뒤 MySQL StatefulSet을 재시작한다. 애플리케이션과 마이그레이션 Job에는 root
  키를 주입하지 않는다.
- MySQL TLS/CA: CA 중첩 기간을 두고 다음 순서를 지킨다. 먼저 이전+새 CA를 함께 담은
  `baton-go-mysql-client-tls` Secret과 필요하면 갱신한 JDBC URL을 담은
  `baton-go-database-client-config` Secret을 배포한다. 실행 중인 마이그레이션
  Job은 없어야 하며 다음 Job도 이 이중 신뢰 저장소를 사용한다. Secret 적용 뒤 아래 명령으로
  애플리케이션 Pod를 모두 재생성하고 기존 서버 인증서 연결과 준비 상태를 확인한다.

  ```bash
  kubectl -n baton-go rollout restart deployment/baton-go
  kubectl -n baton-go rollout status deployment/baton-go --timeout=10m
  ```

  그 다음 새 CA가 서명한 서버 인증서 Secret을 적용하고 MySQL을 재시작해 애플리케이션 재연결,
  `Ssl_cipher`, 잘못된 CA·호스트 부정 검증을 확인한다. 마지막으로 이전 CA를 제거한 클라이언트
  신뢰 저장소를 배포하고 위 두 명령으로 애플리케이션 Pod를 다시 재생성한다. 준비 상태와 새 CA
  연결을 확인한 뒤에만 중첩을 종료한다. 서버부터 회전하거나 Secret 볼륨 파일 변경만으로
  MySQL/Hikari가 인증서를 즉시 다시 읽는다고 가정하지 않는다.
- HMAC 비밀값: `baton-go-link-code-secret`은 버전별 키 묶음 도입 전에는 일반 회전 대상으로
  취급하지 않는다. 검증된 DB 복원을 수행하는 경우에만 같은 Secret 버전을 사용한다. 유출·오용이
  의심되면 Secret을 갱신하거나 rollout restart를 먼저 하지 않고 `/api/v1`과
  `/l`, 호출자 아웃박스를 차단한 뒤
  [ADR-0004 유출 대응](../ADR/0004_link-code-key-binding/adr.md#비밀값-유출-대응)을 따른다.

DB 비밀번호 회전은 단일 계정의 이전/새 비밀번호 전환 구간에 짧은 연결 실패가 생길 수
있으므로 유지 보수 작업으로 수행한다. 무중단 이중 비밀번호 정책이 필요하면 MySQL 계정
정책과 폐기 시점을 별도 ADR/운영 절차로 먼저 정의한다. 실제 비밀번호나 SQL 리터럴은
셸 기록, 프로세스 인자와 작업 로그에 남기지 않는다.

애플리케이션 되돌리기는 이전 변경 불가 이미지와 그 배포가 기대한 설정·스키마 호환성을
확인한 뒤 수행한다.

```bash
kubectl -n baton-go rollout history deployment/baton-go
kubectl -n baton-go rollout undo deployment/baton-go
```

Flyway 마이그레이션은 자동으로 역적용되지 않는다. DB 스키마를 되돌리려고 적용된 마이그레이션을
수정하거나 PVC를 과거 스냅샷으로 단독 복구하지 않는다.

## 8. 백업과 복원

정기 백업 작업의 실행·보존·실패 경보·접근 통제는 배포 환경의 플랫폼 운영 담당자가
소유한다. 이 저장소는 특정 백업 스케줄러나 원격 저장소를 제공하지 않고, 복구 세트의 구성과
복원 검증 절차를 소유한다. 공개 운영 전에는 환경별 플랫폼 백업 정책의 위치와 RPO, RTO,
실행 주기, 보존 기간, 담당자, 실패 경보를 운영 증거에 기록한다. 정책이나 증거 위치가
확정되지 않았으면 공개 운영 관문은 닫힌 상태로 유지한다.

각 백업 증거는 최소한 다음을 하나의 식별 가능한 복구 세트로 묶는다.

- MySQL 인식 논리/물리 백업 또는 일관성이 검증된 볼륨 스냅샷
- 그 시점의 `BATON_GO_LINK_CODE_SECRET` 비밀값 관리자 버전
- 애플리케이션 배포 이미지 다이제스트와 Flyway 스키마 버전
- DB 클라이언트 설정, 런타임, 마이그레이션과 초기 설정 자격 증명의 비밀값 관리자 버전
- MySQL 서버 인증서·CA와 클라이언트 신뢰 저장소 버전
- 공개/BATON/ROUND 출처 설정 버전

복원 훈련은 격리된 Namespace와 별도 호스트 이름에서 수행한다. 복구한 DB 계정과
런타임·마이그레이션·초기 설정 Secret 버전을 맞추고, 복구 환경 Service DNS를 SAN에 포함한
서버 인증서와 그 CA 신뢰 저장소를 주입한다. 마이그레이션 Job `Complete` 뒤 같은 HMAC Secret
버전으로 시작 보호 장치, 준비 상태와 보관된 검증용 생성 요청의 동일 URL 재생을 검증한다. 전체
단축 URL, 코드 해시, HMAC 지문, JDBC URL이나 Secret 값은 복원 증거에 남기지
않는다.

다음 작업은 일반적인 되돌리기나 정리 명령으로 사용하지 않는다.

- `kubectl delete namespace baton-go`
- `kubectl delete pvc data-baton-go-mysql-0`
- 검증되지 않은 스냅샷으로 PVC만 되돌리기
- DB 없이 HMAC Secret만 과거 또는 새 값으로 바꾸기

PVC 삭제의 유일한 예외는 4절의 세 조건으로 데이터가 없음을 확인하고 승인한 최초 배포의
부분 초기화 복구다. 그 외에는 이름이 같더라도 기존 PVC로 취급해 백업·수동 계정 복구 또는
복원 상향 보고를 사용한다.

StatefulSet 삭제는 기본적으로 PVC를 보존하지만 Namespace 삭제는 해당 Namespace의 PVC를 함께
삭제하고 StorageClass 회수 정책에 따라 실제 볼륨까지 잃을 수 있다. Namespace 매니페스트를
워크로드 Kustomization에서 분리한 이유도 이 연쇄 삭제를 줄이기 위해서다.

## 9. 데이터 보존과 용량

현재 시스템은 만료·폐기된 `smart_links`와 멱등 재생 근거인
`link_creation_requests`를 자동 삭제하지 않는다. `expires_at` 인덱스가 있다는 사실은
정리 정책이나 정리 Job이 있다는 뜻이 아니다. 임의 TTL을 추가하면 다음 계약이
함께 바뀌므로 공개 운영 전에 제품·운영·보안 소유자가 같이 결정한다.

- `Idempotency-Key` 재사용에 대한 동일 단축 URL 재생과 `IDEMPOTENCY_KEY_REUSED`
  보장 기간
- 만료·폐기 후 `410 LINK_EXPIRED`·`410 LINK_REVOKED`를 유지할 기간과
  삭제 후 `404` 전환 허용 여부
- 링크와 멱등 예약을 함께 정리하거나 최소 증거를 남길 tombstone 모델,
  중단 후 재시작·동시 생성·백업 동안의 일관성
- 법정·감사·사고 조사 보존 기간과 백업에서의 최종 제거 시점
- 실제 행 증가율과 백업 크기를 기준으로 한 PVC 사용률·증가 추세 경보,
  StorageClass 확장 가능 여부, 증설 승인·실패 복구 절차

보존 기간과 외부 HTTP 의미를 문서로 승인하고, 정리 알고리즘·멱등성·부하·
복구를 검증하며, 복원 본에서도 같은 정책이 유지됨을 확인하기 전에는 자동 삭제를
배포하지 않는다. 현재 10Gi를 무제한 보존 승인으로 해석하지 않고, 정책 확정 전에는
용량 경보와 승인된 PVC 증설로 대응한다.

## 10. 운영 기능 유지 보수와 운영 공개 관문

대상 계약 목록 조사가 필요한 유지 보수 시간에만 비공개 외부 경계 차단 증거와 쓰기 경로
중지를 먼저 확인한 뒤 운영 기능 두 설정값을 함께 활성화한다. 설정값은 네트워크 경계가
아니며 작업 직후 다시 `false`로 배포한다. 상세 절차는
[대상 계약 v1 정리 실행서](target-contract-v1-remediation.md)를 따른다.

이 실행서의 완료만으로 공개 운영을 승인하지 않는다. 장기 완료 조건은
[교차 서비스 링크 계약의 공개 운영 관문](../PRD/0003_cross-service-link-contract/spec.md#9-공개-운영-관문),
현재 남은 작업은 [인수인계](../../HANDOFF.md#공개-운영-전-남은-관문)를 따른다.

참고:

- [Kubernetes Kustomize](https://kubernetes.io/docs/tasks/manage-kubernetes-objects/kustomization/)
- [Kubernetes StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
- [Kubernetes probe](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- [Kubernetes NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
- [Kubernetes Secret 보안 권고](https://kubernetes.io/docs/concepts/security/secrets-good-practices/)
- [MySQL 공식 이미지 초기화 변수](https://hub.docker.com/_/mysql)
- [MySQL 8.4 원자적 DDL](https://dev.mysql.com/doc/refman/8.4/en/atomic-ddl.html)
- [MySQL Connector/J TLS 설정](https://dev.mysql.com/doc/connectors/en/connector-j-connp-props-security.html)
- [Flyway 스키마 이력 테이블](https://documentation.red-gate.com/fd/flyway-schema-history-table-273973417.html)
