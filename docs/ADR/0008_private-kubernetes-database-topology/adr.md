# ADR-0008: 비공개 Kubernetes의 독립 데이터베이스 구성

- 상태: 채택
- 결정일: 2026-08-08

## 배경

BATON GO는 ADR-0001에서 BATON·ROUND와 분리된 데이터베이스와 배포 수명주기를 갖는
마이크로서비스로 결정했다. 첫 운영 환경은 비공개 Kubernetes 서버이며, BATON의 MySQL
인스턴스·스키마·계정을 공유할지 GO 전용 MySQL을 따로 배포할지 결정해야 한다.

BATON MySQL을 공유하면 초기 인프라 수는 줄지만 백업, 장애, 마이그레이션, 자격 증명과
용량 계획이 다시 BATON 배포와 묶인다. 스키마만 구분해도 root 운영 권한, 저장 볼륨과
복구 시점이 같으면 GO의 DB를 BATON과 독립적으로 운영할 수 없다.

## 결정

- BATON GO Namespace에 단일 복제본 MySQL `StatefulSet`과 헤드리스 `Service`를 둔다.
- MySQL은 GO 전용 `baton_go` 데이터베이스, 런타임 DML 사용자, 마이그레이션 사용자, root 비밀번호와
  `ReadWriteOnce` PVC를 사용한다. BATON의 DB 인스턴스·계정·볼륨을 참조하지 않는다.
- 런타임 사용자 이름 `baton_go`와 마이그레이션 사용자 이름 `baton_go_migrator`는
  비밀값이 없는 변경 불가 ConfigMap에 정의한다. 신규 데이터 디렉터리에서 공식 이미지는 마이그레이션 사용자를
  만들고 이 저장소의 초기화 스크립트는 런타임 사용자에게 `SELECT`, `INSERT`, `UPDATE`, `DELETE`만
  부여한다. 공개 출처·요청 제한 ConfigMap을 별도로 두어 애플리케이션 설정 변경으로 MySQL이
  재시작되지 않게 한다.
- 애플리케이션 Deployment는 Flyway를 비활성화하고 런타임 DML 자격 증명만 받는다. 같은
  배포 이미지의 `--baton-go.migration-only=true` 모드는 컴포넌트 탐색·웹·JPA·도메인 실행기
  없이 DataSource와 Flyway만 시작하고 종료한다. 일회성 Kubernetes Job만 마이그레이션 자격 증명을
  받아 스키마를 적용한다. Spring Boot의 표준 `FlywayMigrationInitializer`가 컨텍스트 시작 중
  마이그레이션과 검증을 수행하며 Flyway 예외는 Job의 0이 아닌 종료 코드로 전파된다. 저장소는
  마이그레이션 이름 검증과 위치 설정 누락 실패만 명시하고 나머지 실행 수명주기는 Flyway와
  Spring Boot 기본 동작을 따른다. 마이그레이션 전용 실행기는
  Flyway 빈이 없으면 성공으로 종료하지 않는다.
- 마이그레이션 Job은 기본적으로 중지된 상태로 만들고 MySQL 준비 상태 확인 뒤 운영자가 한 번만 시작한다.
  Pod 재시작과 Job backoff를 사용하지 않으며 0이 아닌 종료는 자동 재시도하지 않고 실제 스키마와
  Flyway 이력으로 실패 원인을 확인하는 복구 절차를 따른다. 성공 Job은 완료 기록을 보존한 뒤 다음 배포 전에
  수동 삭제하고, 실패 Job과 Pod에는 고정 TTL을 적용하지 않아 장애 조사 자료를 유지한다.
  root 비밀번호는 MySQL Pod에만 주입한다.
- TLS JDBC URL, 런타임 비밀번호, 마이그레이션 비밀번호와 root 비밀번호는 서로 다른 네 Secret에
  보관한다. Kubernetes Secret RBAC는 키 단위가 아니므로 수명주기·권한이 다른 자격 증명을
  한 객체에 합치지 않는다. 애플리케이션은 클라이언트 URL과 런타임 Secret, 마이그레이션 Job은 클라이언트
  URL과 마이그레이션 Secret, MySQL은 런타임·마이그레이션·초기 설정 Secret의 필요한 키만 참조한다.
  링크 코드 HMAC 비밀값은 `baton-go-link-code-secret`에 별도로 보관한다. 관리 API는
  ADR-0010의 발급자 서명 JWT를 검증하므로 공유 관리 토큰 Secret을 두지 않는다.
- PKCS12 신뢰 저장소에는 공개 CA 인증서만 넣고 비밀번호는 자격 증명이 아닌 고정 무결성
  호환값 `baton-go-public-ca-v1`을 사용한다. Hikari DEBUG가 임의 드라이버 속성을 출력할
  수 있으므로 여기에 비밀값을 주입하지 않으며 JDBC URL에도 자격 증명을 포함하지 않는다.
  신뢰 저장소 변경 무결성과 권한은 Kubernetes Secret 객체, 저장 시 암호화와 RBAC가 맡는다.
- 신규 데이터 디렉터리 초기화 시 `MYSQL_ROOT_HOST=localhost`를 사용해 원격 root 계정을
  만들지 않는다. 애플리케이션은 root가 아닌 GO 전용 사용자로만 연결한다.
- PVC의 `storageClassName`은 지정하지 않는다. 비공개 클러스터의 기본 StorageClass를
  사용하고 실제 공급자, 노드 장애 복구와 스냅샷 정책은 클러스터 운영 계층에서 정한다.
- Kubernetes Kustomize의 `base`와 `overlay` 구조를 사용한다. Secret 값, 레지스트리 자격 증명,
  실제 TLS 인증서, Ingress 컨트롤러 주석과 환경별 호스트는 `base`에 넣지 않는다.
  `base`는 외부 MySQL 서버 TLS Secret과 클라이언트 신뢰 저장소 Secret의 이름·키·마운트 계약만
  정의한다.
- Namespace 매니페스트는 워크로드 Kustomization에서 분리한다.
  워크로드를 제거할 때 Namespace와 PVC까지 함께 삭제되는 것을 막는다.
- 저장소의 HTTP Service는 `ClusterIP:8080`만 제공한다. 외부 프록시는 공개 `/l` Prefix와
  비공개 `/api/v1` Prefix를 같은 Service의 서로 다른 경로로 전달한다. Actuator `8081`은
  Ingress와 Service로 기본 노출하지 않고 제한된 모니터링·운영 접근에만 사용한다.
- 생존·준비 탐침은 Spring Boot의 `management.endpoint.health.probes.add-additional-paths`
  설정으로 주 HTTP 포트 `8080`의 `/livez`·`/readyz`를 사용한다. 주 포트가 요청을 처리하지
  못할 때 별도 관리 포트만 정상이라는 이유로 Pod를 정상으로 판단하지 않도록 한다.
  Kubernetes 시작·준비 탐침과 Docker 상태 확인은 `/readyz`, 생존 탐침은 `/livez`를 사용한다.
  DB는 준비 상태에만 포함하며 두 경로는 Ingress에 노출하지 않는다.
- 애플리케이션 수신 NetworkPolicy는 `8080`을 명시적으로 승인한 Namespace에만 허용한다.
  `8081`은 승인한 Namespace 안에서 actuator-client label을 가진 Pod에만 허용한다. kubelet의
  노드-to-Pod probe 처리와 host-network ingress 동작은 CNI별 사전 검증이 필요하며 selector가
  HTTP 경로별 접근 제어를 제공하지 않는다.
- MySQL 수신은 NetworkPolicy로 같은 Namespace의 BATON GO 애플리케이션 Pod에서 오는
  TCP 3306과 일회성 마이그레이션 Job만 허용한다. 실제 집행은 NetworkPolicy를 지원하는 CNI가
  전제다.
- MySQL은 외부 PKI가 발급한 서버 인증서를 사용하고
  `require_secure_transport=ON`으로 TCP 평문 연결을 거부한다. 애플리케이션과 마이그레이션 Job은
  전용 PKCS12 신뢰 저장소를 마운트하고 Connector/J `sslMode=VERIFY_IDENTITY`로 Service DNS를
  검증한다. 인증서 SAN은 JDBC host인 `baton-go-mysql`을 반드시 포함한다.
- MySQL 계정 초기화는 공식 진입점과 Kustomize `configMapGenerator`가 만든 저장소
  런타임 사용자 초기화 스크립트를 사용한다. 서버 인증서와 키 검증은 PKI 발급 계층과 mysqld
  시작, 호스트 이름 검증은 Connector/J `VERIFY_IDENTITY`가 담당한다. startup·readiness probe는
  런타임 계정의 로컬 TCP TLS 세션으로 실행해 socket만 살아 있는 상태를 Ready로 보지 않는다.
- 초기 운영은 애플리케이션과 MySQL 모두 단일 복제본이다. MySQL HA와 다중 노드 저장소 장애 조치는
  별도 운영 결정이 필요하다. Redis 분산 요청 제한은 [ADR-0013](../0013_distributed-public-resolver-quota/adr.md)을
  따르며 기본 비활성화 상태다. 운영 Redis 연결과 검증은 [분산 요청 제한 절차](../../RUNBOOK/distributed-public-rate-limit.md)를 따른다.

## 결과

장점:

- BATON GO 마이그레이션, 장애, 용량과 복구 시점을 BATON 데이터베이스와 독립적으로 운영한다.
- 장기 실행 애플리케이션 Pod에는 런타임 DML 권한만 주입하고 마이그레이션 자격 증명은 일회성
  Job, root 자격 증명은 MySQL Pod로 노출 범위를 제한한다.
- Namespace 내부 DB TCP 연결도 TLS 서버 식별 정보 검증을 거치므로 CNI 정책과 독립된
  암호화·인증 계층을 갖는다.
- 특정 Kubernetes 배포판, Ingress 컨트롤러와 StorageClass에 저장소가 종속되지 않는다.

비용과 제약:

- MySQL Pod, PVC, 백업·복원과 역할별 DB/TLS Secret을 별도로 운영해야 한다. Secret 객체
  수는 늘지만 `get` 권한과 회전 수명주기가 런타임·마이그레이션·root 사이에서 섞이지 않는다.
- 단일 복제본 MySQL은 Pod 재생성에는 대응하지만 노드·볼륨 장애에 대한 HA를 제공하지
  않는다.
- 공식 MySQL 이미지의 초기화 환경 변수는 빈 데이터 디렉터리에만 적용된다. 기존 PVC의
  권한 분리와 자격 증명 회전은 MySQL 계정 변경과 Secret 배포를 함께 수행하는 별도
  절차가 필요하다.
- 공식 이미지는 초기화 도중 실패해도 이미 시스템 스키마가 생긴 데이터 디렉터리를 기존
  데이터베이스로 판단해 다음 시작에서 초기화 스크립트를 건너뛸 수 있다. PVC 삭제·재생성은 데이터가
  없다고 확인된 최초 배포에만 허용하고, 데이터 존재 가능성이 있으면 백업 뒤 승인된
  MySQL 관리 채널에서 계정을 수동 복구한다.
- 인증서 발급·SAN, 신뢰 저장소 생성·교체와 CA 회전은 클러스터 PKI 운영 담당자가 관리한다.
- 표준 NetworkPolicy는 HTTP 경로, 노드 호스트 방화벽과 일부 host-network 경로를 제어하지
  못하므로 CNI와 외부 프록시를 각각 검증하고 필요한 정책을 추가해야 한다.
- 배포 매니페스트는 실행 자원만 정의하며 공개 운영 승인을 대신하지 않는다. PRD-0003의
  세션·참여 허가·외부 프록시·기존 데이터는 계속 별도로 점검해야 한다.
