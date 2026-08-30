# Prometheus 경보 연결과 검증

## 적용 범위

[경보 규칙](../../deploy/prometheus/baton-go-alerts.yml)은 기존 Actuator·Micrometer 지표로
애플리케이션의 5xx, 관리 JWT 검증 서비스 장애, 공개 해석 요청 제한과 저장 대상 계약 위반을 감지한다.
규칙 추가만으로 수집기나 Alertmanager가 배포되지는 않는다. 실제 운영 수집기·알림 경로·
담당자 연결과 발화 시험은 [배포 실행서의 수집과 경보 관문](kubernetes-private-server-deployment.md#수집과-경보-관문)을 따른다.

## 수집 계약

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
정본은 YAML 규칙이며, 표와 테스트도 같은 변경에서 갱신한다.

| 경보 | 발생 조건 | 우선순위 |
| --- | --- | --- |
| `BatonGoHttpServerErrors` | 최근 5분 5xx 5건 이상·429 제외 응답의 오류율 5% 초과가 5분 지속 | `critical` |
| `BatonGoManagementAuthenticationServiceFailure` | 최근 5분 관리 JWT 검증 서비스 장애 카운터 증가 | `warning` |
| `BatonGoPublicResolverRateLimited` | 최근 5분 GET·HEAD 429 10건 이상이 5분 지속 | `warning` |
| `BatonGoStoredTargetContractViolation` | 최근 5분 계약 위반 카운터 증가 | `critical` |

- 5xx 계산에서는 `/actuator...`를 제외하고, 오류율 분모에서도 429 응답을 제외한다.
  요청 제한으로 차단된 요청이 늘어도 실제 처리한 요청의 서버 오류율이 낮아지지 않도록 한다.
  DB 준비 상태와 수집 중단은 별도 인프라 경보로 감시한다. 트래픽이 없거나 수집이 끊겼다고
  이 경보들이 자동으로 발화하지 않는다.
- 관리 JWT 검증 서비스 장애는
  `baton_go_management_authentication_service_failures_total`로 따로 집계한다. JWK 조회 등
  인증 서비스 장애만 포함하고 토큰 누락·서명·클레임 검증 실패인 401과 권한 부족인 403은
  포함하지 않는다. 공개 링크 성공 요청이 많아 전체 5xx 비율이 낮아져도 경보를 발생시킨다.
  `requestId`로 안전한 오류 로그를 찾고 발급자 상태와 Pod의 JWK HTTPS 접근을 확인한다.
- 429는 MVC 이전 필터에서 반환될 수 있으므로 `uri="/l/{code}"`로 제한하지 않는다.
  Ingress에서 차단된 요청은 애플리케이션에 도달하지 않으므로 Ingress 지표로 별도 감시한다.
- 계약 위반은 `baton_go_public_resolver_target_contract_violations_total`로 수집한다.
  애플리케이션 시작 시 0으로 등록하여 오류 발생 전 기준값을 수집할 수 있게 한다.
  `increase`에는 관측값이 두 개 이상 필요하므로 최초 수집 전 오류나 수집 공백은 로그로도
  확인한다. 카운터가 초기화되어도 저장 데이터 문제가 해결된 것은 아니다.
- 증가량은 수집 간격을 보정한 추정치다. 감사용 요청 건수나 청구 집계로 사용하지 않는다.
  관리 인증 서비스 장애 카운터도 시작 시 0으로 등록하며 최초 수집 전 장애와 수집 공백은
  로그로 함께 확인한다. 최근 5분에 새 장애가 관측되지 않으면 경보가 해제되지만, 관리 요청이
  없었던 것만으로 인증 서비스가 복구되었다고 판단하지 않는다.
- Job 실패, Pod 상태, DB 준비 상태, PVC·백업 경보는 플랫폼 지표로 따로 연결한다.
  이 파일을 적용했다고 전체 운영 관문을 통과한 것으로 기록하지 않는다.

## 로컬·CI 검증

저장소 루트에서 실행한다. CI는 [공식 배포본](https://prometheus.io/download/)의
Prometheus 3.13.2 이미지 다이제스트를 고정하여 같은 명령을 실행한다.

```bash
promtool_image=prom/prometheus:v3.13.2@sha256:508729e0e2d18e11fd742a5a5ca70e557b940a93948c3c95fd0123a6fd538b69

docker run --rm --network none --read-only \
  --tmpfs /tmp:rw,nosuid,nodev,size=256m \
  --volume "$PWD/deploy/prometheus:/rules:ro" --workdir /rules \
  --entrypoint /bin/promtool "$promtool_image" check rules baton-go-alerts.yml

docker run --rm --network none --read-only \
  --tmpfs /tmp:rw,nosuid,nodev,size=256m \
  --volume "$PWD/deploy/prometheus:/rules:ro" --workdir /rules \
  --entrypoint /bin/promtool "$promtool_image" test rules baton-go-alerts.test.yml
```

[규칙 테스트](../../deploy/prometheus/baton-go-alerts.test.yml)는 무트래픽·소수 오류,
Actuator·다른 서비스 제외, 지속 시간, 경보 회복과 계약 위반 카운터 초기화 후 재발을 확인한다.
5xx와 대량 429가 함께 발생해도 서버 오류 경보를 유지하고, 429만 발생하면 요청 제한 경보만
발생하는지도 검증한다. 관리 인증 서비스 장애는 대량 공개 성공 요청과 무관한 발화,
카운터 초기화 뒤 재발과 새 장애가 없는 구간의 해제를 검증한다.
CI의 운영 이미지 기동 검증은 실제 `/actuator/prometheus` 출력에 초기 계약 위반·관리 인증 장애 카운터와
필터가 반환한 429가 포함되는지도 확인한다. 테스트 문법은
[Prometheus 규칙 단위 테스트](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/)를 따른다.

운영 적용 후에는 target `UP`, 규칙 로딩, Alertmanager 라우팅과 담당자 수신을 각각 확인한다.
실제 저장 데이터를 훼손해 오류를 만들지 말고 격리된 환경의 합성 시계열·시험 알림을 사용한다.
규칙 버전, 발화·회복 시각, 알림 수신·확인 결과를 배포 증거에 기록한다.
