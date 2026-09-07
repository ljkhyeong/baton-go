# Prometheus 경보 연결과 검증

## 적용 범위

[경보 규칙](../../deploy/prometheus/baton-go-alerts.yml)은 기존 Actuator·Micrometer 지표로
공개·관리 응답 지연, 전체·관리 API 5xx, 관리 JWT 검증 서비스 장애, 관리 링크 복구 오류,
단축 링크 요청 제한과 저장 대상 계약 위반을 감지한다.
규칙 추가만으로 수집기나 Alertmanager가 배포되지는 않는다. 실제 운영 수집기 연결,
알림 경로·수신자 설정과 경보 발생 테스트는 [배포 문서의 수집·경보 확인 절차](kubernetes-private-server-deployment.md#수집과-경보-확인-사항)를 따른다.

## 추가 서비스 요금 없는 연결

기존 서버의 Prometheus·Alertmanager와 기존 알림 채널을 재사용한다. 유료 SaaS, 무료 체험,
사용량 과금 API나 새 서버를 추가하지 않는다. 수집·경보에는 기존 서버의 CPU·메모리·저장 공간이
필요하다. 이미 수집 중이면 중복 job을 만들지 않고 아래 설정과 비교한다.

1. [수집 설정 예시](../../deploy/prometheus/prometheus-kubernetes.example.yml)의 `rule_files`와
   `scrape_configs`를 기존 Prometheus 설정에 병합한다. 기존 `alerting`, 저장 기간과 다른 수집
   작업은 유지한다. 경보 파일은 Prometheus 설정 파일과 같은 디렉터리에 마운트하거나
   `rule_files`를 실제 마운트 경로로 바꾼다.
2. Kubernetes 내부 Prometheus를 기준으로 `baton-go` Namespace의 애플리케이션 Pod에서
   `actuator` 포트만 자동 발견한다. Namespace가 다르면 수집 설정과 아래 Role의 Namespace를
   함께 바꾼다. 준비되지 않은 실행 중 Pod도 수집하여 장애를 숨기지 않는다.
3. 기존 Prometheus ServiceAccount에 Pod 조회 권한이 없다면
   [조회 권한 설정](../../deploy/k8s/monitoring-access/prometheus-pod-reader.yaml)의 `subjects`를
   실제 ServiceAccount·Namespace로 수정한 뒤 적용한다. 예시의 `monitoring/prometheus`는
   운영 환경을 확인한 값이 아니다. GO Namespace의 Pod `get/list/watch`만 허용하며 Secret 읽기나
   클러스터 전체 권한을 부여하지 않는다. Prometheus에는 해당 ServiceAccount 토큰이 필요하다.

   ```bash
   kubectl kustomize deploy/k8s/monitoring-access
   kubectl apply -k deploy/k8s/monitoring-access
   ```

4. 수집기 Namespace에는 `baton-go.networking/allow-actuator: "true"`, 수집기 Pod의
   배포 템플릿에는 `baton-go.networking/actuator-client: "true"` label을 설정한다.
   GO의 `8081`을 공개하거나 기존 NetworkPolicy를 완화하지 않는다.
5. Prometheus의 기존 `alerting.alertmanagers`가 운영 Alertmanager를 가리키는지 확인한다.
   Alertmanager에서 GO 경보가 기존 수신자로 전달되는 경로를 사용한다. 전용 경로가 필요하면
   다음을 기존 `route.routes`에 병합하고 `receiver`를 등록된 운영 수신자 이름으로 바꾼다.
   앞선 일치 경로가 알림을 가로채지 않는지도 확인한다.

   ```yaml
   - matchers: ['job="baton-go"']
     receiver: REPLACE_WITH_EXISTING_RECEIVER
     group_by: [job, alertname]
     group_wait: 30s
     group_interval: 5m
     repeat_interval: 4h
   ```

   기존 무료 채널만 사용하고 발송용 Java 코드를 추가하지 않는다. 수신자가 없으면 전달 준비가
   끝난 것으로 보지 않는다. 토큰·SMTP 비밀번호는 기존 비밀값 관리 경로에 둔다.
6. 병합한 설정을 `promtool check config`, `amtool check-config`로 확인한 뒤 기존 운영 절차로
   다시 읽는다. Pod별 `up{job="baton-go"}=1`, 경보 파일 로딩과 기존 수신자 라우팅을 확인한다.
   시험 알림은 담당자와 정한 채널에서 발생·해제 수신까지 확인한다.

이 설정은 한 운영 환경의 GO를 대상으로 한다. 여러 환경을 하나의 Prometheus에 수집하려면
아래 Prometheus 수집 설정에 따라 환경별 label과 경보 집계를 먼저 구분한다.
Prometheus 자체가 중단되면 이 규칙도 평가되지 않으므로 수집기 장애 감시는 기존 플랫폼 감시에 연결한다.
표준 기능은 [Prometheus Kubernetes 수집](https://prometheus.io/docs/prometheus/latest/configuration/configuration/#kubernetes_sd_config)과
[Alertmanager 알림 경로](https://prometheus.io/docs/alerting/latest/configuration/#route)를 따른다.

## Prometheus 수집 설정

- 한 운영 환경의 BATON GO Pod들을 `job="baton-go"`로 수집한다. 다른 환경을 같은 job으로
  합산하지 않는다. 중앙 수집기에 여러 환경을 모으려면 환경 label을 선택 조건과
  `sum by (...)`에 함께 추가하고 같은 변경에서 규칙 테스트도 갱신한다.
- 대상은 `app.kubernetes.io/name=baton-go`, `app.kubernetes.io/component=application`인
  각 Pod의 `actuator` 포트 `8081`, 경로 `/actuator/prometheus`다. 하나의 부하 분산 주소를
  수집하여 Pod별 카운터가 번갈아 나타나게 하지 않는다. `instance`는 Pod별로 유지한다.
- 수집 간격은 30초 이하로 설정한다. 규칙은 30초마다 평가하고 최근 5분 증가량을 사용한다.
  실제 지표 이름·`status`·`method`·`uri` label이 아래 조건과 일치하는지 수집기에서 확인한다.
- 수집기 Namespace에는 `baton-go.networking/allow-actuator=true`, 수집기 Pod에는
  `baton-go.networking/actuator-client=true`가 필요하다. 실제 CNI의 허용·차단을 확인하며
  Actuator를 공개 Ingress로 노출하지 않는다.
- 원문 공개 코드, 전체 URL, 대상 경로나 사용자 입력을 추가 label로 만들지 않는다.
  `uri`는 프레임워크가 기록하는 경로 패턴을 유지한다.

수집기의 기존 설정에 다음과 같이 규칙 파일을 연결한다. 경로는 수집기 컨테이너 내부 경로다.

```yaml
rule_files:
  - /etc/prometheus/rules/baton-go-alerts.yml
```

규칙 파일을 읽기 전용으로 마운트하고 수집기 관리 절차에 따라 설정을 다시 읽거나 재시작한다.
Prometheus Operator를 사용하는 환경은 같은 규칙을 `PrometheusRule`에 옮기고 해당
Prometheus의 rule selector가 선택하는지 확인한다. 저장소는 특정 운영자 CRD에 의존하지 않는다.

## 초기 경보 기준

임계치는 초기 운영값이다. 트래픽 기준선과 응답 담당자의 대응 기준을 확인한 뒤 조정한다.
YAML 규칙을 기준으로 하며, 표와 테스트도 같은 변경에서 갱신한다.

| 경보 | 발생 조건 | 우선순위 |
| --- | --- | --- |
| `BatonGoMetricsScrapeFailed` | 특정 Pod의 지표 수집 실패가 2분 지속 | `critical` |
| `BatonGoMetricsTargetMissing` | GO의 `up` 시계열 부재가 2분 지속 | `critical` |
| `BatonGoPublicResolverLatency` | 최근 5분 공개 요청 100건 이상·1초 초과 비율 5% 초과가 5분 지속 | `warning` |
| `BatonGoManagementApiLatency` | 최근 5분 관리 요청 20건 이상·2초 초과 비율 5% 초과가 5분 지속 | `warning` |
| `BatonGoHttpServerErrors` | 최근 5분 5xx 5건 이상·429 제외 응답의 오류율 5% 초과가 5분 지속 | `critical` |
| `BatonGoManagementApiServerErrors` | 최근 5분 관리 API 5xx 5건 이상·429 제외 관리 응답의 오류율 5% 초과가 5분 지속 | `critical` |
| `BatonGoManagementAuthenticationServiceFailure` | 최근 5분 관리 JWT 검증 서비스 장애 카운터 증가 | `warning` |
| `BatonGoManagementLinkRecoveryFailure` | 최근 5분 관리 링크 복구 오류 카운터가 코드별로 증가 | `critical` |
| `BatonGoPublicResolverRateLimited` | 최근 5분 GET·HEAD 429 10건 이상이 5분 지속 | `warning` |
| `BatonGoStoredTargetContractViolation` | 최근 5분 계약 위반 카운터 증가 | `critical` |
| `BatonGoLinkRetentionFailure` | 최근 5분 링크 정리 실패 증가 | `warning` |
| `BatonGoDistributedResolverQuotaFailure` | 최근 5분 공용 제한 저장소 장애 증가 | `critical` |

- 5xx 계산의 분자·분모에서 `/actuator...`, `/livez`, `/readyz`를 제외하고, 오류율 분모에서도
  429 응답을 제외한다. 상태 확인 성공이나 요청 제한 응답이 늘어도 업무 요청의 서버 오류율이
  낮아지지 않도록 하며, 준비 상태의 503을 업무 요청의 5xx에 합산하지 않는다.
  DB 준비 상태는 별도 인프라 경보로 감시한다. HTTP 경보는 트래픽이 없거나 수집이 끊기면
  발생하지 않으므로 위의 수집 실패·대상 누락 경보를 함께 적용한다. 대상이 사라진 뒤 누락
  경보까지는 시계열의 stale 처리 시간과 2분 지속 시간이 걸린다.
- 관리 API 5xx 경보는 매핑된 `/api/v1/**` 응답만 분자·분모에 사용한다. 공개 성공 요청이
  많아 전체 오류율이 낮아져도 DB 쓰기 권한·링크 코드 설정 등 관리 작업 장애를 감지한다.
  전체 서비스 장애 때는 전체·관리 5xx 경보가 함께 발생할 수 있다. 인증 필터에서 끝나
  `uri="UNKNOWN"`으로 기록되는 JWK 장애는 아래의 관리 JWT 전용 경보가 담당한다.
- 관리 JWT 검증 서비스 장애는
  `baton_go_management_authentication_service_failures_total`로 따로 집계한다. JWK 조회 등
  인증 서비스 장애만 포함하고 토큰 누락·서명·클레임 검증 실패인 401과 권한 부족인 403은
  포함하지 않는다. 공개 링크 성공 요청이 많아 전체 5xx 비율이 낮아져도 경보를 발생시킨다.
  `requestId`로 안전한 오류 로그를 찾고 발급자 상태와 Pod의 JWK HTTPS 접근을 확인한다.
- 관리 링크 복구 오류는 `baton_go_management_link_recovery_failures_total`에 아래 네 가지
  `code`만 사용해 집계한다. 각 카운터를 시작 시 0으로 등록하고, 오류 한 건도 다음 규칙
  평가에서 감지하도록 최소 건수나 지속 시간을 두지 않는다. 최초 수집 전 오류와 수집 공백은
  로그로 함께 확인한다. 5분 동안 새 오류가 없으면 경보가 해제되지만 복구 완료를 뜻하지는 않는다.
  링크 ID·요청 ID·대상 경로·멱등성 키·공개 코드는 지표 label에 넣지 않는다.
  `requestId`로 기존 오류 로그를 찾고 해당 오류의 복구를 완료한 뒤 같은 요청으로 확인한다.
- 현재 버전의 429는 MVC가 `/l/{code}`를 매핑한 뒤 인터셉터가 차단하므로 같은 경로 패턴으로
  기록된다. 기존 필터 버전과 함께 배포하는 동안 `uri="UNKNOWN"` 시계열을 놓치지 않도록
  경보 선택 조건은 `uri`로 제한하지 않는다. Ingress에서 차단된 요청은 애플리케이션에
  도달하지 않으므로 Ingress 지표로 별도 감시한다.
- 계약 위반은 `baton_go_public_resolver_target_contract_violations_total`로 수집한다.
  애플리케이션 시작 시 0으로 등록하여 오류 발생 전 기준값을 수집할 수 있게 한다.
  `increase`에는 관측값이 두 개 이상 필요하므로 최초 수집 전 오류나 수집 공백은 로그로도
  확인한다. 카운터가 초기화되어도 저장 데이터 문제가 해결된 것은 아니다.
- 증가량은 수집 간격을 보정한 추정치다. 감사용 요청 건수나 청구 집계로 사용하지 않는다.
  관리 인증 서비스 장애 카운터도 시작 시 0으로 등록하며 최초 수집 전 장애와 수집 공백은
  로그로 함께 확인한다. 최근 5분에 새 장애가 관측되지 않으면 경보가 해제되지만, 관리 요청이
  없었던 것만으로 인증 서비스가 복구되었다고 판단하지 않는다.
- Job 실패, Pod 상태, DB 준비 상태, PVC·백업 경보는 플랫폼 지표로 따로 연결한다.
  이 파일을 적용했다고 운영 시작 전 점검을 모두 완료한 것으로 기록하지 않는다.

### 관리 링크 복구 오류별 대응

| `code` | 확인할 내용 |
| --- | --- |
| `LINK_CREATION_REPLAY_UNAVAILABLE` | 생성 예약이 가리키는 링크의 누락과 DB 복원·수동 변경 이력을 확인한다. 예약을 삭제하거나 새 링크로 대체하기 전에 저장 일관성을 복구한다. |
| `LINK_CODE_REPLAY_UNAVAILABLE` | 현재 파생 코드와 저장 코드 해시가 일치하지 않는다. 생성 당시 HMAC 비밀값·파생 버전과 데이터 이력을 확인하고 [키·DB 결합 절차](../ADR/0004_link-code-key-binding/adr.md)를 따른다. |
| `LINK_CODE_CONFIGURATION_MISMATCH` | 현재 HMAC 설정이 DB 보호 정보와 일치하는지 확인한다. 비밀값이나 보호 정보를 임의로 덮어쓰지 않고 [키·DB 결합 절차](../ADR/0004_link-code-key-binding/adr.md)를 따른다. |
| `PUBLIC_LINK_ORIGIN_REPLAY_UNAVAILABLE` | 최초 생성 예약의 공개 출처가 없거나 정규 형식이 아니다. 현재 출처를 추정 입력하지 않고 [공개 출처 보존 결정](../ADR/0009_idempotent-public-origin-replay/adr.md)에 따라 최초 출처 증거를 복구한다. |

이 카운터는 HTTP 요청 처리 중 발생한 오류만 집계한다. 시작 단계의 키 결합 실패로 프로세스가
종료된 경우는 Pod·기동 실패 경보와 시작 로그로 확인한다. JWT 인증 장애와 일반 `500`,
`409 IDEMPOTENCY_KEY_REUSED`는 이 카운터에 포함하지 않는다.

### 응답 지연 감지

[애플리케이션 설정](../../bootstrap/src/main/resources/application.yml)의
`management.metrics.distribution.slo.http.server.requests=1s,2s`로 기존 Spring HTTP 타이머에
두 누적 버킷을 추가한다. 별도 요청 타이머나 사용자·링크별 지표는 만들지 않는다.
Prometheus의 `http_server_requests_seconds_bucket`에는 초 단위 `le="1.0"`, `le="2.0"`이
나오며, 경보는 해당 시간 이내 응답의 비율이 95% 미만인지를 계산한다. 이는 보간한 p95가
아니라 정해진 시간을 초과한 응답 비율이다.

- 지연 비율의 분모와 최소 요청량은 해당 버킷이 관측된 요청 수만 사용한다.
  `count`와 버킷의 최근 5분 시계열을 `and ignoring (le)`로 연결한 뒤 합산한다.
  `le` 외의 `instance`·`method`·`status`·`uri` 등 기존 라벨은 같아야 한다.
  버킷이 없거나 다른 경계만 제공하는 구버전 Pod의 요청을 분모에 더하지 않는다.
  버킷 값이 0인 시계열도 유지하므로 모든 요청이 기준 시간을 넘는 장애는 계속 감지한다.
- 공개 경보는 `GET·HEAD /l/{code}`, 관리 경보는 매핑된 `/api/v1/**` 요청만 합산한다.
  공개 트래픽이 관리 지연을 가리거나 상태 확인 트래픽이 정상 비율을 높이지 않는다.
- 429는 제외한다. 인증 필터에서 끝나 경로가 `UNKNOWN`인 요청도 지연 경보 대상이 아니므로,
  관리 JWT 서비스 장애와 Ingress 지연은 기존 전용 경보·경계 지표로 함께 확인한다.
- 완료된 요청만 관측하므로 진행 중인 요청 정체나 수집 중단을 이 경보만으로 감지하지 못한다.
  두 경보 모두 최소 요청량과 지속 시간을 요구하며, 무트래픽·저트래픽에서는 발생하지 않는다.
- 1초·2초는 초기 운영 경고 기준이며 사용자에게 약속한 응답 시간이나 요청 제한 시간이 아니다.
  실제 부하와 호출자 대기 시간을 확인해 조정한다. 경계를 바꿀 때는 애플리케이션 버킷,
  경보의 `le`, 설명과 규칙 테스트를 함께 변경한다. 새 버킷을 먼저 배포·수집하고 경보를 바꾼다.
  해당 버킷이 없는 Pod는 집계에서 빠지므로 경보가 없다고 전체 Pod가 정상이라고 판단하지 않는다.
  배포 완료 뒤 모든 Pod에서 해당 버킷이 수집되는지 확인한다.

표준 설정의 의미는 [Spring Boot 지표별 설정](https://docs.spring.io/spring-boot/reference/actuator/metrics.html#actuator.metrics.customizing.per-meter-properties)을 따른다.
시계열 연결은 [Prometheus 집합 연산과 라벨 매칭](https://prometheus.io/docs/prometheus/latest/querying/operators/#logical-set-binary-operators)을 사용한다.

## 로컬·CI 검증

저장소 루트에서 실행한다. CI는 [공식 배포본](https://prometheus.io/download/)의
Prometheus 3.13.2 이미지 다이제스트를 고정하여 같은 명령을 실행한다.

```bash
promtool_image=prom/prometheus:v3.13.2@sha256:508729e0e2d18e11fd742a5a5ca70e557b940a93948c3c95fd0123a6fd538b69

docker run --rm --network none --read-only \
  --volume "$PWD/deploy/prometheus:/rules:ro" --workdir /rules \
  --entrypoint /bin/promtool "$promtool_image" \
  check config --syntax-only prometheus-kubernetes.example.yml

docker run --rm --network none --read-only \
  --tmpfs /tmp:rw,nosuid,nodev,size=256m \
  --volume "$PWD/deploy/prometheus:/rules:ro" --workdir /rules \
  --entrypoint /bin/promtool "$promtool_image" check rules baton-go-alerts.yml

docker run --rm --network none --read-only \
  --tmpfs /tmp:rw,nosuid,nodev,size=256m \
  --volume "$PWD/deploy/prometheus:/rules:ro" --workdir /rules \
  --entrypoint /bin/promtool "$promtool_image" \
  test rules baton-go-alerts.test.yml baton-go-availability.test.yml
```

[규칙 테스트](../../deploy/prometheus/baton-go-alerts.test.yml)는 무트래픽·소수 오류,
Actuator·주 포트 상태 확인·다른 서비스 제외, 지속 시간, 경보 회복과 계약 위반 카운터 초기화 후
재발을 확인한다. 상태 확인 성공이 많아도 업무 요청의 5xx 경보가 유지되는지 검증한다.
5xx와 대량 429가 함께 발생해도 서버 오류 경보를 유지하고, 429만 발생하면 요청 제한 경보만
발생하는지도 검증한다. 관리 API 5xx 경보는 대량 공개 성공·관리 429에도 발생하는지, 소량 오류와
다른 경로·서비스를 제외하는지, 최소 건수·오류율·지속 시간과 회복을 검증한다.
관리 인증 서비스 장애 경보는 대량 공개 성공 요청과 무관하게 발생하는지,
카운터 초기화 뒤 재발과 새 장애가 없는 구간의 해제를 검증한다.
관리 링크 복구 오류는 한 건 발생, 코드별 구분·Pod 합산, 다른 서비스 제외, 카운터 초기화 뒤
재발과 새 오류가 없는 구간의 해제를 검증한다.
CI의 운영 이미지 기동 검증은 실제 `/actuator/prometheus` 출력에 초기 계약 위반·관리 인증 장애·링크 복구 오류 카운터와
MVC 인터셉터가 차단한 429와 공개 경로의 1초·2초 지연 버킷이 포함되는지도 확인한다.
지연 규칙은 정상·저트래픽·무트래픽 제외, 공개·관리 경로 분리, 지속 시간과 회복을 검증한다.
혼합 버전에서 정상 응답의 오탐과 최소 요청량 부풀림이 없고 실제 지연은 감지되는지도 확인한다. 테스트 문법은
[Prometheus 규칙 단위 테스트](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/)를 따른다.

[수집 경보 테스트](../../deploy/prometheus/baton-go-availability.test.yml)는 일부 Pod 실패,
다른 서비스 제외, 대상 누락, 지속 시간과 복구 후 해제를 확인한다. 수집 예시는 클러스터 자격
증명 없이 문법을 검증하며, 실제 Pod 발견·접근과 운영 수신자 연결 성공을 대신하지 않는다.

운영 적용 후에는 target `UP`, 규칙 로딩, Alertmanager 라우팅과 담당자 수신을 각각 확인한다.
실제 저장 데이터를 훼손해 오류를 만들지 말고 격리된 환경의 합성 시계열·시험 알림을 사용한다.
규칙 버전, 경보 발생·해제 시각, 알림 수신·확인 결과를 배포 기록에 남긴다.
