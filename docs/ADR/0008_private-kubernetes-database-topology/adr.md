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
- MySQL은 GO 전용 `baton_go` database, application user, root password와
  `ReadWriteOnce` PVC를 사용한다. BATON의 DB instance·계정·volume을 참조하지 않는다.
- application username은 변경 불가 non-secret ConfigMap에서 `baton_go`로 한 번 정의해
  application과 MySQL이 함께 참조한다. public origin·rate-limit 같은 application ConfigMap과
  수명주기를 분리해 앱 설정 변경이 MySQL restart를 만들지 않게 한다. application password와
  MySQL root password는 같은 DB 운영 Secret에 보관하되 root password는 MySQL Pod에만
  주입한다. 관리 Bearer credential과 HMAC 비밀은 별도 runtime Secret으로 분리한다.
- 신규 data directory 초기화 시 `MYSQL_ROOT_HOST=localhost`를 사용해 원격 root 계정을
  만들지 않는다. application은 root가 아닌 GO 전용 user로만 연결한다.
- PVC의 `storageClassName`은 지정하지 않는다. private cluster의 default StorageClass를
  사용하고 실제 provisioner, node 장애 복구와 snapshot 정책은 cluster 운영 계층에서 정한다.
- Kubernetes 기본 Kustomize의 base/overlay 구조를 사용한다. Secret 값, registry credential,
  TLS, Ingress controller annotation과 환경별 host는 base에 넣지 않는다.
- namespace manifest는 workload Kustomization에서 분리한다. workload 제거가 namespace와
  PVC의 연쇄 삭제로 이어지지 않게 하기 위한 의도적 안전 경계다.
- repository의 HTTP Service는 `ClusterIP:8080`만 제공한다. public edge는 `/l` Prefix만,
  private management edge는 `/api/v1` Prefix만 같은 Service로 라우팅한다. Actuator `8081`은
  Ingress와 Service로 기본 노출하지 않고 kubelet probe와 제한된 운영 접근에만 사용한다.
- MySQL ingress는 NetworkPolicy로 같은 namespace의 BATON GO application Pod에서 오는
  TCP 3306만 허용한다. 실제 집행은 NetworkPolicy를 지원하는 CNI가 전제다.
- 초기 운영은 application과 MySQL 모두 단일 replica다. MySQL HA, distributed resolver
  rate limit과 multi-node storage failover는 별도 운영 결정 전에는 구현됐다고 보지 않는다.

## 결과

장점:

- BATON GO migration, 장애, 용량과 복구 시점을 BATON 데이터베이스와 독립적으로 운영한다.
- application Pod에는 최소 DB 권한만 주입하고 root credential 노출 범위를 MySQL Pod로
  제한한다.
- 특정 Kubernetes 배포판, Ingress controller와 StorageClass에 저장소가 결합되지 않는다.

비용과 제약:

- MySQL Pod, PVC, backup·restore와 두 종류 Secret을 별도로 운영해야 한다.
- 단일 replica MySQL은 Pod 재생성에는 대응하지만 node·volume 장애에 대한 HA를 제공하지
  않는다.
- 공식 MySQL image의 초기화 환경 변수는 빈 data directory에만 적용된다. 기존 PVC의
  credential 회전은 MySQL account 변경과 Secret rollout을 함께 수행하는 별도 절차가 필요하다.
- 배포 매니페스트는 runtime 기반일 뿐 public production 승인 증거가 아니다. PRD-0003의
  session·grant·edge·inventory gate는 계속 별도로 통과해야 한다.
