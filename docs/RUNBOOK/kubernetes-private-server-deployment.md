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
| `Secret/baton-go-runtime-credentials` | 관리 Bearer 토큰, 링크 HMAC 비밀값 | 애플리케이션 Pod만 참조 |
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
   확인한다. 지원하지 않으면 애플리케이션·MySQL 접근 제한을 방화벽 또는 CNI 정책으로 별도
   보완한다. host-network ingress controller를 쓰면 출처 selector 적용 여부도 확인한다.
5. DB 백업 대상, 보존 기간, 복원 담당자와 HMAC Secret 버전의 공동 복구 단위를
   확정한다.
6. Kubernetes Secret의 저장 시 암호화를 활성화한다. `baton-go` Namespace의 Secret
   `get/list/watch`, Pod `exec/attach/ephemeralcontainers`, Pod·Deployment·StatefulSet·Job의
   `create/update/patch`는 직접 또는 워크로드 마운트를 통해 Secret을 읽을 수 있는 권한으로
   취급하고 승인된 배포·운영 주체에만 최소 부여한다. Secret 객체는 base64 인코딩만으로
   보호되지 않으며 클러스터·노드 관리자와 워크로드 생성 권한자는 별도 신뢰 경계다.
7. MySQL 서버 인증서를 발급할 PKI와 CA 회전 담당자를 정한다. 인증서 SAN에는
   JDBC URL의 host인 `baton-go-mysql`을 반드시 넣고, 필요하면
   `baton-go-mysql.baton-go.svc`와 클러스터 도메인 FQDN도 함께 넣는다. IP SAN만으로 대체하지
   않는다.

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

`deploy/k8s/overlays/private-server/app-config.properties`에서 세 `REPLACE_ME` 값을 실제
출처로 바꾼다.

- `BATON_GO_PUBLIC_BASE_URL`: 사용자에게 반환할 단축 URL의 HTTPS 출처
- `BATON_GO_BATON_BASE_URL`: BATON 공개 HTTPS 출처
- `BATON_GO_ROUND_BASE_URL`: BATON과 같은 HTTPS 출처

비로컬 BATON·ROUND 출처는 scheme, host와 port까지 같아야 한다. 경로, query,
fragment와 userinfo를 넣지 않는다. 운영 기능 두 설정값은 평상시에 모두 `false`로 둔다.
`SPRING_FLYWAY_ENABLED=false`는 장기 실행 애플리케이션에서 변경하지 않는다. Flyway는 아래의
일회성 마이그레이션 Job만 실행한다.

`deploy/k8s/overlays/private-server/kustomization.yaml`의 `registry.invalid/baton-go`와
`replace-with-immutable-release`를 검토·서명된 배포 이미지로 교체한다. 이동하는 `latest`
태그 대신 변경 불가 태그를 `newTag`에 사용한다. 레지스트리가 digest를 제공하면 `newTag`를
제거하고 같은 이미지 항목에 `digest: sha256:<검증한 digest>`를 사용한다. digest 문자열을
`newTag` 값에 넣지 않는다. 오버레이는 선택한 공식 MySQL 8.4.10 다중 아키텍처 이미지
다이제스트로 고정하며, 변경은 백업·복원 검증을 포함한 별도 DB 업그레이드로 다룬다.

비공개 레지스트리를 사용하는 기본 오버레이는 `imagePullSecret` 이름
`baton-go-registry`를 참조한다. 노드 수준 레지스트리 인증이나 미리 적재한 이미지를 쓰는
환경만 `app-private-registry-patch.yaml` 적용을 제거한다.

렌더링 결과에는 Secret 객체나 값, 인증서와 신뢰 저장소가 들어가지 않는다. 기본본은 외부
Secret의 이름과 키만 참조하므로 3절의 구체화가 먼저 완료되어야 실제 Pod가
시작한다.

```bash
kubectl kustomize deploy/k8s/overlays/private-server >/dev/null
```

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

비밀값 관리자 또는 External Secrets controller를 사용한다면 다음 이름과 키로 구체화한다.

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

- 관리 토큰은 32자 이상의 공백 없는 출력 가능 ASCII이며, 신규 값은 base64url처럼 HTTP
  헤더와 파서에 안전한 알파벳으로 생성한다.
- HMAC 비밀값은 관리 토큰과 다른 32자 이상의 새 무작위 값이며 base64url 또는 16진수처럼
  파서에 안전한 알파벳으로 생성한다.
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
  호환값인 고정 문자열 `baton-go-public-ca-v1`을 사용한다. 애플리케이션은 이 값을 Hikari 드라이버
  속성으로 이미 제공한다. Hikari DEBUG가 임의 드라이버 속성을 출력할 수 있으므로
  이 값을 비밀값으로 바꾸거나 다른 자격 증명을 같은 속성에 넣지 않는다. 신뢰 저장소 객체의
  변경 무결성과 접근 제한은 Secret 저장 시 암호화와 RBAC로 보장한다.

런타임 사용자 초기화 스크립트는 런타임 사용자 이름과 비밀번호의 안전한 SQL 알파벳을 확인한 뒤
로컬 root socket으로 DML 전용 권한을 만든다. 마이그레이션·root 비밀번호도 같은 파서 안전
알파벳으로 생성한다. 인증서/키 쌍과 SAN은 발급 PKI에서 검증하고, 실제 배포에서는
mysqld startup·TLS readiness와 Connector/J `VERIFY_IDENTITY`가 잘못된 값을 거부한다. CI는
동일한 초기화 스크립트로 런타임 계정의 DML 4종과 DDL 거부를 검증한다.

```text
jdbc:mysql://baton-go-mysql:3306/baton_go?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/etc/baton-go/mysql-tls/truststore.p12&trustCertificateKeyStoreType=PKCS12&fallbackToSystemTrustStore=false&serverTimezone=UTC
```

`useSSL=false`, `sslMode=DISABLED|PREFERRED`, `allowPublicKeyRetrieval=true`, 다른 host와
`fallbackToSystemTrustStore=true` 및 `trustCertificateKeyStorePassword`를 운영
`BATON_GO_DB_URL`에 넣지 않는다. 애플리케이션과 마이그레이션 Job은 같은 URL·공개 CA 신뢰 저장소를
사용하되 서로 다른 데이터베이스 사용자 이름/비밀번호를 사용한다.

Kubernetes Secret의 RBAC는 객체 키 단위가 아니다. 따라서 URL, 런타임, 마이그레이션과 root를
별도 객체로 유지한다. 운영자·컨트롤러 RBAC도 가능하면 `resourceNames`로 필요한 Secret만
허용하며 네 Secret을 하나로 합치지 않는다. Secret 읽기 권한이 없어도 Pod나 워크로드 템플릿을
만들거나 바꿀 수 있으면 해당 Secret을 마운트해 읽을 수 있으므로 워크로드 변경과
`exec/attach/ephemeralcontainers` 권한도 같은 감사 범위에 포함한다.

외부 Secret controller가 없다면 저장소 밖의 접근 제한된 파일을 사용할 수 있다.
파일에는 위 키를 `KEY=VALUE` 형식으로 넣고 권한을 `0600`으로 제한한다. 값을 명령 인자,
셸 기록, 티켓 또는 CI 로그에 넣지 않는다. `kubectl`은 파일을 데이터로 읽으며 `source`로
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
kubectl -n baton-go describe secret baton-go-runtime-credentials
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

Kubernetes는 애플리케이션, 마이그레이션 Job과 MySQL의 생성 순서를 보장하지 않는다. 마이그레이션
Job은 DB가 준비될 때까지 실패를 재시도하고 애플리케이션은 마이그레이션 전 스키마 검증에
실패하면 Pod 재시작 정책으로 재시도한다. 준비 상태가 성공하기 전에는 Service 엔드포인트가
되지 않는다. Job이 `Complete`가 되지 않으면 애플리케이션 배포를 성공으로 판단하지 말고
Job Pod의 종료 원인과 MySQL TLS·자격 증명을 먼저 확인한다.

마이그레이션 전용 실행기는 일반 컴포넌트 탐색 없이 DataSource와 Flyway 자동 설정만
연다. Spring Boot의 표준 `FlywayMigrationInitializer`가 컨텍스트 시작 중 마이그레이션을 수행하고,
마이그레이션·검증 예외는 Job의 0이 아닌 종료 코드로 전파된다. Job에 임의 대상, 기준선 또는
건너뛰기 설정을 추가하지 않는다. Job은 Flyway를 명시적으로 활성화하며, Flyway 빈이 없으면
마이그레이션 전용 실행기도 성공으로 종료하지 않는다. 저장소는 마이그레이션 이름 검증과 위치 설정
누락 실패를 명시한다.

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
보존한다. 키 링을 도입하기 전에는 HMAC 비밀값만 회전하지 않는다.

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
4. 관리 Bearer 토큰, Authorization, `Idempotency-Key`, 대상 경로와 전체 단축 URL을
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
재생, 공개 해석과 폐기를 검증한다. 실제 관리 토큰, `Idempotency-Key`, 공개 코드,
대상 경로와 전체 단축 URL을 셸 기록이나 검증 증거에 복사하지 않는다. 증거에는 HTTP
상태, 링크 ID, 요청 ID와 시각처럼 허용된 메타데이터만 남긴다.

## 7. 업데이트와 되돌리기

애플리케이션 이미지와 비밀값 제외 ConfigMap 변경은 Kustomize를 다시 적용한다. ConfigMap 이름에
내용 해시가 붙어 Pod 템플릿이 바뀌므로 설정 변경도 새 배포를 만든다. 마이그레이션 Job의
Pod 템플릿은 변경 불가이고 같은 배포 이미지를 사용한다. 이전 Job이 남아 있으면 먼저
완료·실패 상태와 필요한 비밀값 제외 메타데이터를 보존하고, 실행 중이 아님을 확인한 뒤 Job만
삭제한다. `ttlSecondsAfterFinished=3600`은 보조 정리이며 배포 직전 삭제 확인을 대신하지
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

위 삭제는 완료되거나 실패한 마이그레이션 Job 객체만 대상으로 하며 StatefulSet, PVC, Namespace와
Secret에는 사용하지 않는다. Job이 아직 실행 중이면 중단하지 말고 원인을 확인한다. 한 번의
Kustomize 적용은 Job과 Deployment 순서를 보장하지 않으므로 모든 Flyway 변경은 구 애플리케이션과
구 스키마에 호환되는 확장 단계여야 한다. Job `Complete` 확인 뒤 새 애플리케이션을 검증하고,
열/테이블 제거 같은 축소 단계는 모든 구 Pod와 읽기 프로세스가 사라진 후 별도 배포에서
수행한다. 애플리케이션이 마이그레이션보다 먼저 시작해 스키마 검증에 실패하는 짧은 구간은
재시작으로 복구되지만, 이를 파괴적 마이그레이션 허용 근거로 사용하지 않는다.

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
다시 읽지 않는다. Secret 회전은 다음 수명주기별 절차를 따른다.

- 관리 토큰: 런타임 Secret의 해당 키를 갱신하고
  `kubectl -n baton-go rollout restart deployment/baton-go`를 실행한다. 배포 뒤 새 토큰은
  성공하고 이전 토큰은 `401`인지 비공개 경로에서 확인한다.
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
- MySQL TLS/CA: CA 중첩 기간을 두고 다음 순서를 지킨다. 먼저 이전+새 CA를 함께 담은 이중
  신뢰 저장소와 필요하면 갱신한 client-config JDBC URL을 배포하고 애플리케이션을 배포해 기존
  서버 인증서 연결과 준비 상태를 확인한다. 실행 중인 마이그레이션 Job은 없어야 하며 다음
  Job도 이 이중 신뢰 저장소를 사용한다. 그 다음 새 CA가 서명한 서버 인증서 Secret을
  적용하고 MySQL을 재시작해 애플리케이션 재연결, `Ssl_cipher`, 잘못된 CA·호스트 부정 검증을
  확인한다. 마지막으로 이전 CA를 제거한 클라이언트 신뢰 저장소를 배포하고 애플리케이션을 다시
  배포한 뒤에만 중첩을 종료한다. 서버부터 회전하거나 Secret 볼륨 파일 변경만으로
  MySQL/Hikari가 인증서를 즉시 다시 읽는다고 가정하지 않는다.
- HMAC 비밀값: 키 링 도입 전에는 일반 회전 대상으로 취급하지 않는다. DB와 함께 검증된
  복원을 수행하는 경우에만 같은 Secret 버전을 사용한다.

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

## 9. 운영 기능 유지 보수와 운영 공개 관문

대상 계약 목록 조사가 필요한 유지 보수 시간에만 비공개 외부 경계 차단 증거와 쓰기 경로
중지를 먼저 확인한 뒤 운영 기능 두 설정값을 함께 활성화한다. 설정값은 네트워크 경계가
아니며 작업 직후 다시 `false`로 배포한다. 상세 절차는
`docs/RUNBOOK/target-contract-v1-remediation.md`를 따른다.

최종 공개 운영 전에는 다음이 여전히 별도 완료 조건이다.

- 배포 DB 전체 목록 조사와 `unrevoked non-compliant=0`, 미승인 `HOLD=0` 증거
- BATON 세션·CSRF·참여 허가와 회의실 매핑/종료 표식
- 공개 `/l` Prefix 전용 외부 경계, 비공개 `/api/v1` Prefix 경계와 접근 로그 마스킹
- 호출자 아웃박스/취소 표식과 분산 요청 제한
- 실제 백업·복원 훈련과 장애·되돌리기 훈련

참고:

- [Kubernetes Kustomize](https://kubernetes.io/docs/tasks/manage-kubernetes-objects/kustomization/)
- [Kubernetes StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
- [Kubernetes probe](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- [Kubernetes NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
- [Kubernetes Secret 보안 권고](https://kubernetes.io/docs/concepts/security/secrets-good-practices/)
- [MySQL 공식 이미지 초기화 변수](https://hub.docker.com/_/mysql)
- [MySQL Connector/J TLS 설정](https://dev.mysql.com/doc/connectors/en/connector-j-connp-props-security.html)
