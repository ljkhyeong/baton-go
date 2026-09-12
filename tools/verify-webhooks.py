#!/usr/bin/env python3
"""운영 웹훅 예시를 외부 통신 없는 임시 Alertmanager와 수신 서버로 검증한다."""

import argparse
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time
from urllib.error import URLError
from urllib.parse import parse_qs, urlsplit, urlunsplit
from urllib.request import Request, urlopen
import uuid


PYTHON_IMAGE = "python:3.14.7-alpine3.23@sha256:8caa2adfeb414dfe68d8b257f7aea9e205a400521c2b13b2d2e5e731fb8e70e5"
CHANNELS = {
    "slack": (9093, "slack-webhook-url"),
    "discord": (9094, "discord-webhook-url"),
    "healthchecks": (9095, "healthchecks-ping-url"),
}
PRIVATE_MARKER = "private-value-not-for-notification"


def wait_until(predicate, description, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.1)
    raise RuntimeError(description + " 시간 초과")


def exercise(output):
    receipts = []
    limited = set()
    lock = threading.Lock()

    class Receiver(BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            endpoint = urlsplit(self.path)
            channel = endpoint.path.removeprefix("/")
            encoded = json.dumps(body, ensure_ascii=False)
            with lock:
                if "BatonGoRedirectProbe" in encoded:
                    status = 307
                elif channel not in limited:
                    limited.add(channel)
                    status = 503 if channel == "healthchecks" else 429
                else:
                    status = 200
                receipts.append({"channel": channel, "status": status, "body": body, "query": endpoint.query})
            self.send_response(status)
            if status == 429:
                self.send_header("Retry-After", "1")
            if status == 307:
                self.send_header("Location", "http://127.0.0.1:18080/unexpected")
            self.send_header("Content-Type", "application/json" if channel == "discord" else "text/plain")
            self.end_headers()
            replies = {"discord": b'{"id":"fixture"}', "healthchecks": b"OK"}
            self.wfile.write(replies.get(channel, b"ok"))

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 18080), Receiver)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    def ready(port):
        try:
            with urlopen(f"http://127.0.0.1:{port}/-/ready", timeout=1) as response:
                return response.status == 200
        except (URLError, TimeoutError):
            return False

    def received(channel, status, title):
        with lock:
            return any(item["channel"] == channel and item["status"] == status
                       and title in json.dumps(item["body"]) for item in receipts)

    def heartbeats():
        with lock:
            return [item for item in receipts if item["channel"] == "healthchecks"]

    def send(port, *, resolved=False, job="baton-go", name="BatonGoReadinessFailed"):
        now = datetime.now(timezone.utc)
        alert = {
            "labels": {"job": job, "alertname": name, "private_label": PRIVATE_MARKER},
            "annotations": {"summary": "GO 준비 상태 확인 실패", "description": PRIVATE_MARKER},
            "startsAt": (now - timedelta(minutes=1)).isoformat(),
            "endsAt": (now + timedelta(seconds=-1 if resolved else 300)).isoformat(),
            "generatorURL": "https://private.example/" + PRIVATE_MARKER,
        }
        request = Request(f"http://127.0.0.1:{port}/api/v2/alerts",
                          data=json.dumps([alert]).encode(),
                          headers={"Content-Type": "application/json"})
        with urlopen(request, timeout=3) as response:
            assert response.status == 200

    try:
        for channel, (port, _) in CHANNELS.items():
            wait_until(lambda: ready(port), channel + " Alertmanager 준비")
            if channel == "healthchecks":
                heartbeat = {"job": "baton-go-monitoring", "name": "BatonGoMonitoringHeartbeat"}
                send(port, **heartbeat)
                wait_until(lambda: sum(item["status"] == 200 for item in heartbeats()) >= 2,
                           "Healthchecks 503 재전송·주기 신호")
                previous_count = len(heartbeats())
                send(port, resolved=True, **heartbeat)
                send(port, job="another-service", name=heartbeat["name"])
                send(port, job=heartbeat["job"], name="AnotherAlert")
                # 테스트 반복 간격(1초)을 넘겨 해제·필터 불일치 신호가 전달되지 않는지 확인한다.
                time.sleep(1.5)
                assert len(heartbeats()) == previous_count, "종료·필터 불일치 경보가 성공 신호로 전송됨"
                continue
            send(port)
            wait_until(lambda: received(channel, 200, "[firing]"), channel + " 발생 알림·429 재전송")
            send(port, resolved=True)
            wait_until(lambda: received(channel, 200, "[resolved]"), channel + " 해제 알림")
            send(port, name="BatonGoRedirectProbe")
            wait_until(lambda: received(channel, 307, "BatonGoRedirectProbe"), channel + " 리다이렉트 응답")
            send(port, job="another-service", name="UnmatchedProbe")
        # 최초 그룹 대기 시간(100ms)이 지난 뒤 제외 경로와 리다이렉트 후속 전송을 확인한다.
        time.sleep(1)
        with lock:
            observed = list(receipts)
        assert all(item["channel"] in CHANNELS for item in observed), "리다이렉트 주소로 전송됨"
        assert all("UnmatchedProbe" not in json.dumps(item["body"]) for item in observed), "다른 서비스 알림 전송됨"
        assert all(PRIVATE_MARKER not in json.dumps(item["body"]) for item in observed), "비공개 값이 알림에 포함됨"
        for item in observed:
            body = item["body"]
            if item["channel"] == "healthchecks":
                assert body == {"signal": "monitoring-heartbeat"}, "감시 신호의 고정 본문 변경됨"
                continue
            if item["channel"] == "discord":
                assert parse_qs(item["query"]).get("wait") == ["true"], "Discord 저장 확인 옵션 누락"
            message = body["attachments"][0] if item["channel"] == "slack" else body["embeds"][0]
            assert "BATON GO" in message["title"]
            text = message["text"] if item["channel"] == "slack" else message["description"]
            assert "GO 준비 상태 확인 실패" in text
        report = {
            "result": "passed",
            "channels": list(CHANNELS),
            "checks": {
                "slack_discord": ["발생·해제 알림", "429 이후 다음 주기 전송", "Discord 저장 확인 옵션",
                                  "리다이렉트 미전송", "다른 서비스 제외", "비공개 값 제외"],
                "healthchecks": ["고정 본문", "503 재전송", "주기 신호", "해제 신호 미전송", "필터 불일치 제외"],
            },
            "requests": {channel: sum(item["channel"] == channel for item in observed) for channel in CHANNELS},
        }
        (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(report, ensure_ascii=False), flush=True)
    finally:
        server.shutdown()
        server.server_close()


def run(output):
    root = Path(__file__).resolve().parents[1]
    workflow = (root / ".github/workflows/ci.yml").read_text()
    image = re.search(r"^\s+ALERTMANAGER_IMAGE: (\S+)$", workflow, re.MULTILINE).group(1)
    # 이미지 다운로드 시간은 수신 서버의 연결 제한 시간에 포함하지 않는다.
    for required_image in (PYTHON_IMAGE, image):
        cached = subprocess.run(["docker", "image", "inspect", required_image],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if cached.returncode != 0:
            subprocess.run(["docker", "pull", required_image], check=True)
    output.mkdir(parents=True, exist_ok=True)
    for channel, (_, url_file) in CHANNELS.items():
        source = (root / f"deploy/prometheus/alertmanager-{channel}.example.yml").read_text()
        fixture = source.replace("/etc/alertmanager/secrets/", "/state/")
        fixture = fixture.replace("group_wait: 30s", "group_wait: 100ms")
        fixture = fixture.replace("group_interval: 5m", "group_interval: 1s")
        fixture = fixture.replace("group_interval: 15s", "group_interval: 200ms")
        fixture = fixture.replace("repeat_interval: 1m", "repeat_interval: 1s")
        (output / f"{channel}.yml").write_text(fixture)
        example_url = (root / f"deploy/prometheus/{url_file}.example").read_text().strip()
        endpoint = urlunsplit(("http", "127.0.0.1:18080", "/" + channel, urlsplit(example_url).query, ""))
        (output / url_file).write_text(endpoint + "\n")
    prefix = "baton-go-webhooks-" + uuid.uuid4().hex[:12]
    fixture_name = prefix + "-receiver"
    containers = []

    def start(name, arguments):
        containers.append(name)
        subprocess.run([
            "docker", "run", "--detach", "--name", name, "--read-only",
            "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
            "--user", f"{os.getuid()}:{os.getgid()}",
            "--tmpfs", "/tmp:rw,nosuid,nodev,size=32m,mode=1777",
            "--volume", f"{output}:/state", *arguments,
        ], check=True, stdout=subprocess.DEVNULL)

    try:
        start(fixture_name, [
            "--network", "none", "--volume", f"{Path(__file__).resolve()}:/verify.py:ro",
            PYTHON_IMAGE, "python", "-u", "/verify.py", "--fixture", "--output", "/state",
        ])
        for channel, (port, _) in CHANNELS.items():
            start(prefix + "-" + channel, [
                "--network", "container:" + fixture_name, image,
                "--config.file=/state/" + channel + ".yml", "--storage.path=/tmp/data",
                "--cluster.listen-address=", f"--web.listen-address=127.0.0.1:{port}",
            ])
        result = subprocess.run(["docker", "wait", fixture_name], check=True, capture_output=True, text=True, timeout=150)
        if result.stdout.strip() != "0":
            raise RuntimeError("웹훅 검증 실패: receiver.log 확인")
        print((output / "report.json").read_text(), end="")
    finally:
        for name in reversed(containers):
            logs = subprocess.run(["docker", "logs", name], capture_output=True, text=True)
            (output / (name.removeprefix(prefix + "-") + ".log")).write_text(logs.stdout + logs.stderr)
            subprocess.run(["docker", "rm", "--force", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print("검증 자료: " + str(output), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="임시 설정·로그·결과 저장 경로")
    parser.add_argument("--fixture", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    destination = (args.output or Path(tempfile.mkdtemp(prefix="baton-go-webhooks-"))).resolve()
    if args.fixture:
        exercise(destination)
    else:
        run(destination)
