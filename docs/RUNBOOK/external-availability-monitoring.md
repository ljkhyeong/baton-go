# 외부 접속·감시 시스템 중단 알림

홈서버 밖에서 공개 HTTPS 접속과 감시 시스템의 주기 신호를 확인한다.
서버·Prometheus·Alertmanager가 함께 멈춰도 외부 서비스에서 Slack 알림을 보낼 수 있다.
두 연동은 선택 사항이며 계정·수신 채널을 연결한 뒤 활성화한다.

## HetrixTools 외부 접속 감시

[등록 예시](../../deploy/uptime/hetrixtools-go.example.json)는 HetrixTools의 Website Monitor API 형식이다.
대시보드에서 같은 값으로 등록해도 된다. 공개 HTTPS 배포가 준비된 뒤 다음 순서로 연결한다.

1. 무료 계정에서 Slack 웹훅을 연결한 Contact List를 만든다. 기존 Slack 앱을 재사용할 수 있으며
   웹훅 URL은 저장소에 넣지 않는다. API로 등록한다면 예시의 `ContactList`를 실제 목록 ID로 바꾼다.
2. 다음 조건으로 감시 대상을 한 번 등록한다. 같은 대상이 있으면 추가하지 말고 수정한다.

   | 항목 | 설정 |
   | --- | --- |
   | 대상 | `https://go.b4ton.com/l/external-uptime-probe` |
   | 요청 | `GET`, 리다이렉트 추적 안 함 |
   | 정상 응답 | HTTP `404`이면서 본문에 `링크를 찾을 수 없습니다` 포함 |
   | 주기·대기 | 1분 간격, 응답 대기 10초 |
   | 감시 위치 | 도쿄·싱가포르·암스테르담·뉴욕 |
   | 장애 판정 | 4개 위치 중 3개 실패, 연속 실패 3회 |
   | TLS | 인증서 신뢰·호스트 이름 검증, 만료 7일 전 알림 |
   | 보고서 | 비공개, 대상 주소 표시 안 함 |

3. Cloudflare와 Ingress가 이 요청을 GO까지 전달하는지 확인한다. `/l` 응답의 `Cache-Control: no-store`를
   유지하고 해당 경로를 캐시하는 규칙은 제외한다. 봇 인증 화면이나 접근 제한으로 차단된다면
   필요한 감시 경로에 한해 조정한다. 관리 API·Actuator·`/readyz`를 공개할 필요는 없다.
4. 모니터가 정상으로 표시되고 실제 선택한 Contact List가 연결됐는지 확인한다.
   시험 대상에서 잘못된 본문이나 실패 응답으로 장애를 만든 뒤 Slack 발생·복구 알림까지 확인한다.
   시험 후 대상과 정상 응답 조건을 원래 값으로 복원한다.

`external-uptime-probe`는 발급 코드의 22자 형식에 맞지 않아 실제 링크가 될 수 없다.
기존 공개 오류 처리 경로를 사용하며 새 엔드포인트나 사용자 링크를 만들지 않는다.
404 상태만 확인하면 프록시 오류 페이지를 정상으로 오인할 수 있으므로 본문도 확인한다.
CI는 같은 JSON의 경로·문구·정상 상태를 실제 GO 이미지의 HTML·JSON 응답과 대조한다.

이 감시는 DNS·공개 TLS·프록시·GO 오류 응답 경로를 확인한다. DB 조회 성공, 실제 링크 리다이렉트와
BATON·ROUND의 인증은 기존 내부 감시·연동 검증으로 확인한다. 요청은 기존 요청률 제한에 포함되므로
감시 트래픽을 감당할 여유를 두며 `429`를 정상 코드로 추가하지 않는다.
Cloudflare 프록시를 쓰면 외부 TLS 검사는 Cloudflare가 제공하는 인증서를 본다.
Origin 인증서 상태는 기존 cert-manager 경보와 구분한다.

무료 플랜은 감시 대상 15개, 1분 주기를 제공하며 계정 유지를 위해 90일마다 대시보드에 로그인해야 한다.
유료 문자·전화 알림은 사용하지 않는다. API를 사용할 때도 주기적 등록·조회 스크립트를 두지 않는다.
등록·변경용 API 토큰이 포함된 주소는 비밀값으로 취급하고 명령행·로그에 출력하지 않는다.

근거: [공식 등록 API](https://docs.hetrixtools.com/api-add-website-ping-service-smtp-uptime-monitor/),
[정상 HTTP 코드 지정](https://docs.hetrixtools.com/website-uptime-monitoring-accepted-http-codes/),
[Slack 연동](https://docs.hetrixtools.com/slack-integration/),
[무료 조건](https://hetrixtools.com/pricing/uptime-monitor/).

## Healthchecks.io 감시 시스템 중단 알림

Prometheus가 항상 활성인 경보를 만들고 Alertmanager가 약 1분 간격으로 Healthchecks.io에 신호를 보낸다.
신호가 끊기면 Healthchecks.io가 Slack으로 알린다. GO의 개별 장애는 기존 GO 경보가 담당한다.
기존에 같은 감시 경로의 Watchdog 연동이 있다면 새 신호를 추가할 필요가 없다.
반복 간격은 1분이며 재전송 여부는 15초마다 확인한다. 처리 시간에 따라 실제 간격은 더 길어질 수 있다.

1. 무료 계정에 `BATON GO monitoring` 체크를 하나 만든다. Simple 일정의 Period는 `1분`,
   Grace Time은 `4분`으로 설정한다. 마지막 신호 후 약 5분간 다음 신호가 없으면 장애로 판단한다.
   Filtering Rules에서 `Only POST`를 선택하고 본문 키워드 판정은 끈다.
2. 이 체크에 Slack 연동을 연결한다. Ping URL은 기본 성공 주소 `https://hc-ping.com/<uuid>`를 사용한다.
   URL 자체가 신호를 보낼 권한이므로 Secret으로 보관하고 Alertmanager에
   `/etc/alertmanager/secrets/healthchecks-ping-url` 파일로 읽기 전용 마운트한다.
   런타임에 Healthchecks 관리 API 키는 필요하지 않다.
3. [주기 신호 규칙](../../deploy/prometheus/baton-go-monitoring-heartbeat.yml)을 읽기 전용으로 마운트하고
   기존 Prometheus의 `rule_files`에 추가한다. 이 규칙은 기본 GO 수집 예시에 자동 포함되지 않는다.
4. [Alertmanager 예시](../../deploy/prometheus/alertmanager-healthchecks.example.yml)의 하위 route와
   `healthchecks-go` receiver만 기존 설정에 병합한다. 다른 경보를 모두 받는 route보다 앞에 두고,
   기존 최상위 route·Slack·Discord receiver는 유지한다. 예시의 `unmatched`는 옮기지 않는다.
   `job`과 `alertname` 두 조건이 모두 맞을 때만 주기 신호로 전달한다.
5. 아래 검사 후 기존 운영 절차로 설정을 다시 읽는다. 예시는 CI와 같은 Alertmanager `0.34.0`의
   `payload` 기능을 사용한다. 실제 버전이 다르면 지원 여부를 검사하고, 검사 실패를 피하려고
   `payload`를 지워 기본 경보 본문을 보내지 않는다.

   ```bash
   promtool check config /etc/prometheus/prometheus.yml
   amtool check-config /etc/alertmanager/alertmanager.yml
   amtool config routes test --config.file=/etc/alertmanager/alertmanager.yml \
     --verify.receivers=healthchecks-go \
     job=baton-go-monitoring alertname=BatonGoMonitoringHeartbeat
   ```

6. Healthchecks 이벤트 기록에서 약 1분 간격의 수신을 확인한다. HTTP 200만으로 등록 성공을 판단하지 않는다.
   Healthchecks는 존재하지 않는 Ping URL에도 200을 반환할 수 있다.
   시험 환경에서 이 경보만 잠시 차단해 Slack 장애 알림을 받고, 차단 해제 후 복구 알림까지 확인한다.
   다른 GO 장애 알림이나 모니터링 서버 전체를 중단할 필요는 없다.

전송 본문은 `{"signal":"monitoring-heartbeat"}`뿐이다. 내부 주소·label·경보 설명은 포함하지 않으며
신호 해제 알림은 보내지 않는다. 해제된 경보를 성공 신호로 보내면 중단 감지가 늦어질 수 있다.
Prometheus와 Alertmanager의 연결·웹훅 전송이 살아 있는지 확인하는 설정이며,
모든 수집 대상과 개별 Slack receiver가 정상이라는 뜻은 아니다.

계획된 점검 때는 Healthchecks 체크를 일시 중지하고 `Ignore the ping, stay in the paused state`를
선택한다. 점검 후 다시 활성화하고 수신을 확인한다. 연동을 제거할 때는 체크·규칙·route·receiver를
함께 정리한다. 여러 운영 환경은 체크와 신호를 분리한다.

무료 플랜의 체크 한도는 20개다. Slack 무료 플랜의 앱 설치 한도도 기존
[Slack 연동 조건](external-api-integrations.md#slack으로-go-장애-알림)을 따른다.
백업 실행 경로가 정해지면 같은 서비스에 별도 체크를 만들어 시작·성공·실패·미실행을 감시할 수 있다.
백업 완료 신호가 실제 복원 가능성을 보장하지는 않는다.

근거: [주기·유예 시간](https://healthchecks.io/docs/configuring_checks/),
[Ping API](https://healthchecks.io/docs/http_api/),
[Alertmanager 웹훅](https://prometheus.io/docs/alerting/latest/configuration/#webhook_config),
[Slack 연동](https://healthchecks.io/integrations/add_slack/), [무료 조건](https://healthchecks.io/pricing/).

## 적용 상태

저장소의 설정과 CI 검증만 준비했다. 실제 서비스 계정·체크 생성, Slack 수신 채널 연결,
홈서버 적용과 외부 장애·복구 수신은 아직 실행하지 않았다.
