# 추가 요금 없는 외부 API 연동

GO에서 직접 구현을 줄일 수 있는 연동과 적용 설정을 정리한다.
기준 환경은 `b4ton.com`, 서비스별 하위 도메인, Ubuntu 홈서버의 k3s다.
서버 설치·DNS 변경·인증서 발급·알림 전송은 이 작업에서 실행하지 않았다.

## 검토 결과

| 대상 | 사용할 연동 | 반영 상태 |
| --- | --- | --- |
| 인증서 갱신 | cert-manager → Cloudflare DNS API + Let's Encrypt ACME | `go.b4ton.com` 발급·갱신 설정 추가. 준비된 인증서를 유지하면 적용하지 않아도 된다. |
| 인증서 장애 알림 | cert-manager 지표 → Prometheus → Discord·Slack | 준비 실패·갱신 지연·만료 임박·지표 누락을 감지하는 선택 설정 추가. |
| 장애 알림 | Alertmanager → Discord·Slack Webhook API | 기존 GO 경보의 발생·해제 알림 설정과 CI 검증 추가. 사용할 채널을 선택한다. |
| CI 도구 갱신 | GitHub Dependabot | GitHub Actions의 새 버전을 주 1회 확인하고 한 PR로 묶는 설정 추가. |
| 빌드 결과 알림 | GitHub 공식 Slack 앱 | 저장소·CI에 맞춘 구독 명령 정리. 실제 채널 구독은 아직 하지 않았다. |
| 관리 API 인증 | Spring Security → 발급자의 JWK Set | 이미 구현됨. 발급자·JWK 주소를 설정하면 서명 키 조회와 캐시를 프레임워크가 처리한다. |
| DNS 레코드 | 기존 Cloudflare DNS | 공인 IP가 유지되면 초기 레코드만 필요하다. 주기적 DNS API 호출은 추가하지 않는다. |
| 링크 생성·폐기 | 기존 GO API | 같은 요청의 URL 복원·활성 시간·폐기·대상 제한을 보장하므로 외부 단축 URL 서비스로 대체하지 않는다. |

공휴일·외부 일정 수집은 GO의 범위가 아니며 이 저장소에는 해당 데이터를 처리하는 기능이 없다.
BATON의 카카오톡 공유·브라우저 QR 기능은
[기존 연동 상태](../../HANDOFF.md#현재-상태)를 따른다.

선정한 연동은 유료 플랜이나 무료 체험을 전제로 하지 않는다. Cloudflare의 기존 DNS와
Let's Encrypt 무료 인증서, Discord·Slack 기본 웹훅을 사용하며 별도 유료 메시지 서비스를 두지 않는다.
API 호출 제한은 각 제공자의 정책을 따르고, Prometheus·Alertmanager·cert-manager의 실행 자원은
기존 홈서버에서 사용한다. 아직 없는 도구는 향후 해당 연동을 선택할 때 준비해야 한다.

근거: [Cloudflare Free](https://www.cloudflare.com/plans/free/),
[Let's Encrypt](https://letsencrypt.org/getting-started/),
[Discord 웹훅](https://support.discord.com/hc/en-us/articles/228383668-Intro-to-Webhooks).

## 도메인 설정

[운영 예시 설정](../../deploy/k8s/overlays/private-server/app-config.properties)에 다음 값을 반영했다.

| 설정 | 값 |
| --- | --- |
| `BATON_GO_PUBLIC_BASE_URL` | `https://go.b4ton.com` |
| `BATON_GO_BATON_BASE_URL` | `https://b4ton.com` |
| `BATON_GO_ROUND_BASE_URL` | `https://b4ton.com` |

`cal.b4ton.com`, `round.b4ton.com`처럼 서비스별 호스트를 둘 수 있다. 다만 현재 BATON 연동형
ROUND 화면은 쿠키·세션 저장소 때문에 BATON과 같은 출처의 `/room/{roomId}`로 열어야 한다.
서비스 호스트를 분리하더라도 GO의 ROUND 대상은 `b4ton.com`을 유지한다.
라우팅 근거는 [교차 서비스 계약](../PRD/0003_cross-service-link-contract/spec.md)이다.
관리 JWT 발급자·JWK 주소는 도메인만으로 확정할 수 없어 기존 필수 설정값을 유지했다.

## Cloudflare DNS API로 인증서 갱신

[연동 설정](../../deploy/k8s/integrations/cloudflare-tls/kustomization.yaml)은
`baton-go` Namespace의 Issuer와 `go.b4ton.com` Certificate만 만든다.
기본 배포에는 포함하지 않으며 cert-manager가 준비된 클러스터에서 선택 적용한다.

1. Cloudflare에서 `b4ton.com` 영역에만 `Zone / DNS / Edit`, `Zone / Zone / Read` 권한이 있는
   API 토큰을 만든다. 전역 API 키는 사용하지 않는다.
2. 비밀값 관리 절차로 `baton-go` Namespace에 `cloudflare-dns-api-token` Secret을 준비한다.
   토큰의 키는 `api-token`이다. Issuer와 Secret은 같은 Namespace에 있어야 한다.
3. 설정을 확인한 뒤 선택 적용한다. 두 번째 명령부터 클러스터가 필요하고, 실제 적용하면
   Cloudflare에 DNS 인증용 TXT 레코드 생성·삭제와 ACME 인증서 발급이 발생한다.

   ```bash
   kubectl kustomize deploy/k8s/integrations/cloudflare-tls
   kubectl apply --dry-run=server -k deploy/k8s/integrations/cloudflare-tls
   kubectl apply -k deploy/k8s/integrations/cloudflare-tls
   kubectl -n baton-go wait --for=condition=Ready issuer/letsencrypt-cloudflare --timeout=2m
   kubectl -n baton-go wait --for=condition=Ready certificate/baton-go-public --timeout=10m
   ```

4. 발급이 완료되면 GO 공개 Ingress의 `spec.tls`에 연결한다.

   ```yaml
   tls:
     - hosts: [go.b4ton.com]
       secretName: baton-go-acme-tls
   ```

기존 인증서 Secret은 덮어쓰지 않는다. 새 인증서를 사용할 때만 Ingress 참조를 전환한다.
갱신과 인증서 개인 키 교체는 cert-manager가 처리한다. 다른 서비스는 해당 Namespace의
Issuer·Secret과 서비스 도메인을 사용하고 GO의 인증서 개인 키를 공유하지 않는다.

DNS-01 검증은 80번 포트나 Cloudflare 프록시 해제 없이 진행할 수 있다. 실제 HTTPS 트래픽은
별도의 DNS·Ingress 경로가 필요하며, 공개 GO 경로는 기존 계약의 `/l`로 제한한다.
준비된 인증서가 Cloudflare Origin CA라면 Cloudflare 프록시와 `Full (strict)`에서 사용한다.
Origin CA 인증서는 브라우저가 직접 신뢰하는 인증서가 아니다.

로컬에서는 cert-manager `v1.21.2`의 공식 CRD 스키마로 두 리소스를 검사했다.
실제 발급·갱신 확인은 토큰을 연결한 환경에서 위 `Ready` 조건과 인증서 만료 시각을 확인해야 한다.
설정 기준: [cert-manager Cloudflare 연동](https://cert-manager.io/docs/configuration/acme/dns01/cloudflare/),
[Cloudflare Origin CA](https://developers.cloudflare.com/ssl/origin-configuration/origin-ca/).

## 인증서 갱신·만료 알림

위 자동 갱신을 사용할 때만 [인증서 수집 설정](../../deploy/prometheus/prometheus-cert-manager.example.yml)과
[경보 규칙](../../deploy/prometheus/baton-go-tls-alerts.yml)을 기존 Prometheus에 함께 연결한다.
인증서 조회나 알림 발송용 애플리케이션 코드는 추가하지 않는다.

| 경보 | 조건 |
| --- | --- |
| 인증서 준비 실패 | Ready가 아닌 상태가 15분 지속 |
| 갱신 지연 | 갱신 예정 시각을 24시간 초과한 상태가 15분 지속 |
| 만료 임박 | 남은 유효 기간이 7일 미만이거나 이미 만료된 상태가 5분 지속 |
| 지표 없음 | GO 인증서 지표가 5분간 수집되지 않음 |

1. 예시의 `rule_files`·`scrape_configs`만 기존 설정에 병합하고 경보 파일을 읽기 전용으로 마운트한다.
   기존 `alerting`과 GO 애플리케이션 수집 설정은 유지한다. 기존 인증서를 계속 사용하거나
   자동 갱신 연동을 제거할 때는 이 job과 규칙을 함께 제외한다.
2. 기본 수집 주소는 `cert-manager.cert-manager.svc:9402/metrics`다. Helm의
   `prometheus.enabled=true`, `prometheus.podmonitor.enabled=false`일 때 생성되는 내부 Service를
   기준으로 하며 실제 Service 이름·Namespace를 맞춘다. 지표에 TLS를 설정했다면 `scheme: https`와
   신뢰할 CA를 지정한다. 네트워크 정책은 Prometheus에서 이 내부 포트로의 접근만 허용한다.
3. 이미 PodMonitor 등으로 수집한다면 job을 중복으로 추가하지 않는다. 규칙의 job과 인증서
   Namespace label을 실제 지표에 맞춘다. 수집기 label과 충돌하면 인증서 Namespace가
   `exported_namespace`로 표시될 수 있다. job을 바꾸면 Alertmanager 조건도 함께 맞춘다.
4. Discord·Slack 예시의 GO route는 `baton-go`와 `baton-go-tls`를 모두 전달한다.
   기존에 `job="baton-go"`만 연결했다면 `job=~"baton-go|baton-go-tls"`로 변경한다.
   병합한 설정의 구문과 인증서 경보의 수신자를 검사한다.

   ```bash
   promtool check config /etc/prometheus/prometheus.yml
   amtool check-config /etc/alertmanager/alertmanager.yml
   amtool config routes test --config.file=/etc/alertmanager/alertmanager.yml \
     --verify.receivers=slack-go job=baton-go-tls alertname=BatonGoCertificateExpiringSoon
   ```

   Discord를 선택했다면 수신자를 `discord-go`로 바꾼다.

적용 후 `up{job="baton-go-tls"}=1`, `baton-go/baton-go-public`의 Ready·만료·갱신 지표와 경보 로딩을
확인한다. 수집 예시는 GO 인증서 지표 3종만 저장하며 Secret·개인 키를 읽지 않는다.
만료·갱신 시각이 아직 없는 최초 발급 대기는 만료나 갱신 지연으로 처리하지 않는다.
현재 인증서가 Ready여도 갱신 예정 시각이 지나면 지연 경보를 낼 수 있다.

감시 대상은 cert-manager의 GO Certificate다. Cloudflare의 공개 인증서, 별도로 준비한
Origin CA 인증서와 실제 HTTPS 라우팅 상태는 이 지표로 확인할 수 없다.
기준: [공식 지표 수집](https://cert-manager.io/docs/devops-tips/prometheus-metrics/),
[인증서 지표 구현](https://github.com/cert-manager/cert-manager/blob/v1.21.2/internal/collectors/certificate_collector.go).

## Discord로 GO 장애 알림

[Discord 설정 예시](../../deploy/prometheus/alertmanager-discord.example.yml)는 기존
[GO 경보](prometheus-alerts.md)를 그대로 전달한다. 발생·해제 건수와 경보 제목만 보내고
전체 label, 내부 주소, 링크 코드와 요청 URL은 메시지에 포함하지 않는다.

1. 알림을 받을 Discord 채널에 웹훅을 만든다. 웹훅 URL 자체가 발송 권한이므로 Secret으로 보관한다.
2. Alertmanager 컨테이너에 해당 값을 UTF-8 파일로 읽기 전용 마운트한다.
   예시 경로는 `/etc/alertmanager/secrets/discord-webhook-url`이다.
3. 공용 Alertmanager에는 예시의 `route.routes` 항목과 `discord-go` receiver만 병합한다.
   기존 최상위 route와 다른 서비스의 receiver는 유지한다. `unmatched`는 GO 전용 예시에서
   다른 서비스 알림을 무시하기 위한 수신자이며 공용 설정에 옮기지 않는다.
4. 앞선 route가 GO 경보를 가로채지 않는지 확인하고 병합 결과를 검사한다.

   ```bash
   amtool check-config /etc/alertmanager/alertmanager.yml
   amtool config routes test --config.file=/etc/alertmanager/alertmanager.yml \
     --verify.receivers=discord-go job=baton-go alertname=BatonGoReadinessFailed
   ```

Alertmanager가 같은 경보를 묶고 반복 알림 간격과 장애 해제 알림을 처리한다.
기존에 다른 알림 채널을 사용한다면 해당 receiver를 유지하면 된다.
프로젝트 CI는 공식 Alertmanager `v0.34.0`으로 Discord·Slack 예시의 구문과 GO·인증서·다른 서비스의 분기를 검사하며
네트워크를 차단해 실제 메시지를 보내지 않는다. 웹훅 파일 읽기와 발생·해제 수신은
선택한 채널을 연결한 뒤 별도로 확인한다.
설정 기준: [Alertmanager Discord 연동](https://prometheus.io/docs/alerting/latest/configuration/#discord_config).

## Slack으로 GO 장애 알림

[Slack 설정 예시](../../deploy/prometheus/alertmanager-slack.example.yml)는 Discord와 같은
GO 경보·그룹화·반복 간격을 사용하고 발생·해제 알림을 모두 보낸다.
본문은 경보 제목과 건수만 표시하며, 알림 미리보기에도 전체 label이나 내부 관리 주소를 넣지 않는다.

1. Slack 앱에서 `Incoming Webhooks`를 켜고 `Add New Webhook to Workspace`로 수신 채널을 선택한다.
   기존 앱이 있으면 재사용한다. 비공개 채널은 웹훅을 등록하는 사용자가 먼저 참여해야 한다.
2. 웹훅 URL을 Secret으로 보관하고 Alertmanager 컨테이너의
   `/etc/alertmanager/secrets/slack-webhook-url`에 UTF-8 파일로 읽기 전용 마운트한다.
3. 공용 Alertmanager에는 예시의 `route.routes` 항목과 `slack-go` receiver만 병합한다.
   기존 최상위 route와 다른 서비스의 receiver는 유지하고, 예시의 `unmatched`는 옮기지 않는다.
4. 앞선 route가 GO 경보를 가로채지 않는지 확인하고 병합 결과를 검사한다.

   ```bash
   amtool check-config /etc/alertmanager/alertmanager.yml
   amtool config routes test --config.file=/etc/alertmanager/alertmanager.yml \
     --verify.receivers=slack-go job=baton-go alertname=BatonGoReadinessFailed
   ```

수신 채널·앱 이름·아이콘은 Slack 웹훅을 만들 때 정해진다. YAML의 `channel`로 바꿀 수 없으므로
예시에는 지정하지 않는다. 채널을 바꾸려면 해당 채널의 웹훅으로 Secret을 교체한다.
Discord와 Slack 양쪽에 보내려면 하나의 GO receiver에 두 예시의 `discord_configs`와
`slack_configs`를 함께 넣고 GO route가 그 receiver를 가리키게 한다.

Slack 무료 플랜에서도 사용할 수 있지만 앱 설치 한도는 외부·사용자 지정 앱을 합쳐 10개다.
기존 앱을 재사용하거나 사용하지 않는 연동을 정리해 한도 내에서 구성하며 유료 전환을 전제로 하지 않는다.
로컬·CI 검증은 실제 웹훅에 접속하지 않는다. 활성화 후 선택한 채널에서 발생·해제 수신을 확인한다.

근거: [Slack 웹훅 등록](https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks/),
[무료 플랜 제한](https://slack.com/help/articles/115002422943-Usage-limits-for-free-workspaces),
[Alertmanager Slack 연동](https://prometheus.io/docs/alerting/latest/configuration/#slack_config).

## CI 도구 업데이트 제안

[Dependabot 설정](../../.github/dependabot.yml)은 매주 월요일 오전 9시(한국 시각)에
`.github/workflows`의 GitHub Actions 업데이트를 확인한다. 커밋 SHA 고정을 유지하며
여러 액션의 변경을 한 PR로 묶고, 열린 버전 업데이트 PR은 최대 1개로 제한한다.
자동 병합은 설정하지 않았다. 기존 CI와 변경 내역을 확인한 뒤 반영한다.

설정이 원격 기본 브랜치에 반영되면 GitHub에서 활성화 여부와 업데이트 작업 결과를 확인한다.
별도 서버·토큰·자체 버전 조회 스크립트는 필요하지 않다. Dependabot 자체는 무료이며,
업데이트 PR에서 실행하는 기존 CI는 저장소의 GitHub Actions 사용량에 포함된다.
추가 유료 플랜이나 전용 실행기는 사용하지 않는다.

다음 항목은 이번 자동 제안 대상에 포함하지 않는다.

- Gradle 의존성: `gradle/verification-metadata.xml`의 체크섬도 함께 갱신·검토해야 한다.
- Dockerfile 기반 이미지: 현재 이미지가 `ARG`에 정의되어 있어 Dependabot의 `FROM` 파서가 읽지 못한다.
- 워크플로 환경 변수·Kustomize에 고정한 이미지 digest: GitHub Actions 액션 갱신과 별도로 관리한다.

이 설정이 전체 의존성이나 이미지의 취약점을 검사하는 것은 아니다. 기존
[이미지 검사 절차](image-security-reports.md)와 [의존성 검증](../../README.md#검증)을 계속 따른다.
근거: [Dependabot 지원 범위](https://docs.github.com/en/code-security/reference/supply-chain-security/supported-ecosystems-and-repositories),
[설정 기준](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference),
[무료 사용 정책](https://github.blog/changelog/2024-04-22-dependabot-updates-on-actions-for-github-enterprise-cloud-and-free-pro-and-teams-users/).

## GitHub CI 결과를 Slack으로 받기

실행 중인 GO의 장애는 Alertmanager가 보내고, 빌드·테스트 결과는 GitHub 공식 Slack 앱이 보낸다.
CI용 웹훅 토큰이나 발송용 워크플로를 별도로 만들지 않는다.

1. Slack의 GitHub 공식 앱을 연결하고 `ljkhyeong/baton-go` 저장소 접근을 허용한다.
   비공개 채널에서는 앱을 초대하고 `/github signin`으로 계정을 연결한다.
2. 수신할 채널에서 아래 명령으로 `main`에 대한 `CI` 실행만 구독한다.

   ```text
   /github subscribe ljkhyeong/baton-go workflows:{name:"CI" event:"pull_request","push" branch:"main"}
   /github subscribe list features
   ```

실행 시작 알림과 완료 결과가 같은 스레드에 표시된다. 실패 결과만 받는 필터는 제공하지 않는다.
기존 저장소 구독의 이슈·커밋 등 다른 알림은 별도 설정이므로 현재 구독 목록에서 확인한다.
구독을 해제하려면 `/github unsubscribe ljkhyeong/baton-go workflows`를 사용한다.
앱 연결과 채널 구독은 이 작업에서 실행하지 않았다. Slack 무료 플랜의 앱 설치 한도는 위와 같다.
근거: [GitHub 공식 Slack 연동](https://github.com/integrations/slack#actions-workflow-notifications).
