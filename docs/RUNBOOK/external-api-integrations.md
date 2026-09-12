# 추가 요금 없는 외부 API 연동

GO에서 직접 구현을 줄일 수 있는 연동과 적용 설정을 정리한다.
기준 환경은 `b4ton.com`, 서비스별 하위 도메인, Ubuntu 홈서버의 k3s다.
서버 설치·DNS 변경·인증서 발급·알림 전송은 이 작업에서 실행하지 않았다.

## 검토 결과

| 대상 | 사용할 연동 | 반영 상태 |
| --- | --- | --- |
| 인증서 갱신 | cert-manager → Cloudflare DNS API + Let's Encrypt ACME | `go.b4ton.com` 발급·갱신 설정 추가. 준비된 인증서를 유지하면 적용하지 않아도 된다. |
| 장애 알림 | Alertmanager → Discord Webhook API | 기존 GO 경보의 발생·해제 알림 설정과 CI 검증 추가. Discord를 사용할 때 선택한다. |
| 관리 API 인증 | Spring Security → 발급자의 JWK Set | 이미 구현됨. 발급자·JWK 주소를 설정하면 서명 키 조회와 캐시를 프레임워크가 처리한다. |
| DNS 레코드 | 기존 Cloudflare DNS | 공인 IP가 유지되면 초기 레코드만 필요하다. 주기적 DNS API 호출은 추가하지 않는다. |
| 링크 생성·폐기 | 기존 GO API | 같은 요청의 URL 복원·활성 시간·폐기·대상 제한을 보장하므로 외부 단축 URL 서비스로 대체하지 않는다. |

공휴일·외부 일정 수집은 GO의 범위가 아니며 이 저장소에는 해당 데이터를 처리하는 기능이 없다.
BATON의 카카오톡 공유·브라우저 QR 기능은
[기존 연동 상태](../../HANDOFF.md#현재-상태)를 따른다.

선정한 연동은 유료 플랜이나 무료 체험을 전제로 하지 않는다. Cloudflare의 기존 DNS와
Let's Encrypt 무료 인증서, Discord 기본 웹훅을 사용하며 별도 유료 메시지 서비스를 두지 않는다.
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
프로젝트 CI는 공식 Alertmanager `v0.34.0`으로 예시의 구문과 GO·다른 서비스의 분기만 검사하며
네트워크를 차단해 실제 메시지를 보내지 않는다. 웹훅 파일 읽기와 발생·해제 수신은
선택한 채널을 연결한 뒤 별도로 확인한다.
설정 기준: [Alertmanager Discord 연동](https://prometheus.io/docs/alerting/latest/configuration/#discord_config).
