# ADR-0008: Private Kubernetes의 독립 데이터베이스 토폴로지

- 상태: 채택
- 결정일: 2026-08-08

## 배경

BATON GO는 ADR-0001에서 BATON·ROUND와 분리된 데이터베이스와 배포 수명주기를 갖는
마이크로서비스로 결정했다. 첫 운영 환경은 private Kubernetes 서버이며, BATON의 MySQL
instance·schema·계정을 공유할지 또는 GO 전용 MySQL을 함께 배포할지 구체화해야 한다.

BATON MySQL을 공유하면 초기 인프라 수는 줄지만 backup, 장애, migration, credential과
용량 계획이 다시 BATON 배포에 결합된다. schema만 구분해도 root 운영 권한, 저장 volume과
복구 시점이 같으면 독립 수명주기라는 경계가 성립하지 않는다.

## 결정

- BATON GO namespace에 단일 replica MySQL `StatefulSet`과 headless `Service`를 둔다.
- MySQL은 GO 전용 `baton_go` database, runtime DML user, migration user, root password와
  `ReadWriteOnce` PVC를 사용한다. BATON의 DB instance·계정·volume을 참조하지 않는다.
- 변경 불가 non-secret ConfigMap에 runtime username `baton_go`와 migration username
  `baton_go_migrator`를 정의한다. 신규 data directory에서 공식 image는 migration user를
  만들고 repository 소유 init script는 runtime user에 `SELECT`, `INSERT`, `UPDATE`, `DELETE`만
  부여한다. application 설정 변경이 MySQL restart를 만들지 않도록 public origin·rate-limit
  ConfigMap과 수명주기를 분리한다.
- application Deployment는 Flyway를 비활성화하고 runtime DML credential만 받는다. 같은
  release image의 `--baton-go.migration-only=true` 모드는 component scan·웹·JPA·domain runner
  없이 DataSource와 Flyway만 시작하고 종료한다. 일회성 Kubernetes Job만 migration credential을
  받아 schema를 적용한다. runner는 context 시작 side effect를 성공으로 간주하지 않고
  Flyway를 `LATEST` target으로만 명시적 실행한 뒤 resolved migration 존재, pending 0건과
  schema history를 검증한다. `CURRENT`·`NEXT`·특정 version target은 일부 DDL을 적용하기
  전에 거부하고, SQL 실행 생략·baseline·선택 적용, migration 이름 또는 migration 전
  validation 비활성화와 기존 baseline state도 허용하지 않는다. Flyway가 비활성화되었거나
  bean을 조립할 수 없으면 Job은 실패한다.
  root password는 MySQL Pod에만 주입한다.
- TLS JDBC URL, runtime password, migration password와 root password는 서로 다른 네 Secret에
  보관한다. Kubernetes Secret RBAC는 key 단위가 아니므로 수명주기·권한이 다른 credential을
  한 object에 합치지 않는다. application은 client URL과 runtime Secret, migration Job은 client
  URL과 migration Secret, MySQL은 runtime·migration·bootstrap Secret의 필요한 key만 참조한다.
  관리 Bearer credential과 HMAC 비밀도 별도 application runtime Secret으로 분리한다.
- PKCS12 truststore에는 공개 CA certificate만 넣고 password는 자격증명이 아닌 고정 integrity
  호환값 `baton-go-public-ca-v1`을 사용한다. Hikari DEBUG가 arbitrary driver property를 출력할
  수 있으므로 여기에 비밀값을 주입하지 않으며 JDBC URL에도 credential을 포함하지 않는다.
  truststore 변경 무결성과 권한은 Kubernetes Secret object, at-rest encryption과 RBAC가 맡는다.
- 신규 data directory 초기화 시 `MYSQL_ROOT_HOST=localhost`를 사용해 원격 root 계정을
  만들지 않는다. application은 root가 아닌 GO 전용 user로만 연결한다.
- PVC의 `storageClassName`은 지정하지 않는다. private cluster의 default StorageClass를
  사용하고 실제 provisioner, node 장애 복구와 snapshot 정책은 cluster 운영 계층에서 정한다.
- Kubernetes 기본 Kustomize의 base/overlay 구조를 사용한다. Secret 값, registry credential,
  실제 TLS certificate, Ingress controller annotation과 환경별 host는 base에 넣지 않는다.
  base는 외부 MySQL server TLS Secret과 client truststore Secret의 이름·key·mount 계약만
  정의한다.
- namespace manifest는 workload Kustomization에서 분리한다. workload 제거가 namespace와
  PVC의 연쇄 삭제로 이어지지 않게 하기 위한 의도적 안전 경계다.
- repository의 HTTP Service는 `ClusterIP:8080`만 제공한다. public edge는 `/l` Prefix만,
  private management edge는 `/api/v1` Prefix만 같은 Service로 라우팅한다. Actuator `8081`은
  Ingress와 Service로 기본 노출하지 않고 kubelet probe와 제한된 운영 접근에만 사용한다.
- application ingress NetworkPolicy는 `8080`을 명시적으로 승인한 namespace에만 허용한다.
  `8081`은 승인한 namespace 안에서 actuator-client label을 가진 Pod에만 허용한다. kubelet의
  node-to-Pod probe 처리와 host-network ingress 동작은 CNI별 preflight가 필요하며 selector가
  HTTP path 경계를 대신하지 않는다.
- MySQL ingress는 NetworkPolicy로 같은 namespace의 BATON GO application Pod에서 오는
  TCP 3306과 일회성 migration Job만 허용한다. 실제 집행은 NetworkPolicy를 지원하는 CNI가
  전제다.
- MySQL은 외부 PKI가 발급한 server certificate를 사용하고
  `require_secure_transport=ON`으로 TCP 평문 연결을 거부한다. application과 migration Job은
  전용 PKCS12 truststore를 mount하고 Connector/J `sslMode=VERIFY_IDENTITY`로 Service DNS를
  검증한다. 인증서 SAN은 JDBC host인 `baton-go-mysql`을 반드시 포함한다.
- MySQL main container보다 먼저 같은 고정 image의 non-root initContainer를 실행한다. 이
  preflight는 data volume을 mount하지 않은 채 database·계정 문법과 서로 다른 password,
  TLS 파일 권한·PEM·certificate/private-key 일치·`baton-go-mysql` DNS SAN·CA 검증, repository
  runtime-user init script의 필수 계정·권한 문장 존재를 확인한다. 실제 추가 SQL의
  안전성과 최소 권한은 exact script를 실행하는 topology smoke가 검증한다. 검증 실패 이유에는 credential
  원문을 포함하지 않으며 main container가 data directory를 만들기 전에 실패한다.
  MySQL startup·readiness probe는 runtime 계정의 local TCP TLS 세션으로 실행해 socket만
  살아 있고 server TLS 설정이 실패한 상태를 Ready로 간주하지 않는다.
- 초기 운영은 application과 MySQL 모두 단일 replica다. MySQL HA, distributed resolver
  rate limit과 multi-node storage failover는 별도 운영 결정 전에는 구현됐다고 보지 않는다.

## 결과

장점:

- BATON GO migration, 장애, 용량과 복구 시점을 BATON 데이터베이스와 독립적으로 운영한다.
- 장기 실행 application Pod에는 runtime DML 권한만 주입하고 migration credential은 일회성
  Job, root credential은 MySQL Pod로 노출 범위를 제한한다.
- namespace 내부 DB TCP 연결도 TLS server identity 검증을 거치므로 CNI 정책과 독립된
  암호화·인증 계층을 갖는다.
- 특정 Kubernetes 배포판, Ingress controller와 StorageClass에 저장소가 결합되지 않는다.

비용과 제약:

- MySQL Pod, PVC, backup·restore와 역할별 DB/TLS Secret을 별도로 운영해야 한다. Secret object
  수는 늘지만 `get` 권한과 회전 수명주기가 runtime·migration·root 사이에서 섞이지 않는다.
- 단일 replica MySQL은 Pod 재생성에는 대응하지만 node·volume 장애에 대한 HA를 제공하지
  않는다.
- 공식 MySQL image의 초기화 환경 변수는 빈 data directory에만 적용된다. 기존 PVC의
  권한 분리와 credential 회전은 MySQL account 변경과 Secret rollout을 함께 수행하는 별도
  절차가 필요하다.
- preflight는 검증 가능한 입력 오류를 최초 초기화 전에 차단하지만, main entrypoint가 system
  schema를 만든 뒤 다른 이유로 실패한 partial data directory를 자동 복구하지 않는다. 공식
  image는 재시작 때 이를 기존 database로 판단해 init script를 건너뛸 수 있다. PVC 삭제·재생성은
  데이터가 없다고 확인된 최초 rollout에만 허용하고, 데이터 존재 가능성이 있으면 backup 뒤
  승인된 MySQL 관리 채널에서 account를 수동 복구한다.
- 인증서 발급·SAN, truststore 생성·교체와 CA rotation은 cluster PKI 운영 계층이 소유한다.
- 표준 NetworkPolicy는 HTTP path, node host firewall과 일부 host-network 경로를 표현하지
  못하므로 CNI·edge별 preflight와 추가 정책이 필요하다.
- 배포 매니페스트는 runtime 기반일 뿐 public production 승인 증거가 아니다. PRD-0003의
  session·grant·edge·inventory gate는 계속 별도로 통과해야 한다.
