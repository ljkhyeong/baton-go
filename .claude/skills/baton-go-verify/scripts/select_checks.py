#!/usr/bin/env python3
"""변경 파일을 docs/RUNBOOK/development-verification.md의 검증 범위에 대응시킨다.

표준 라이브러리만 사용한다. 경로와 Git 상태를 사용하며, 내용은 bootstrap 테스트의 @Tag만 읽는다.
"""

import argparse
import os
import re
import subprocess
import sys
from collections import OrderedDict

ARCH_TEST = ":bootstrap:test --tests '*ArchitectureRulesTest'"

# (경로 정규식, Gradle 작업 목록, 별도 명령·안내 목록)
RULES = [
    (r"^domain/", [":domain:test"], []),
    (r"^application/", [":application:test"], []),
    (r"^adapter-in-web/", [":adapter-in-web:test", ":adapter-in-web:apiContractDocs"], []),
    (r"^adapter-out-external/", [":adapter-out-external:test"], []),
    (r"^adapter-out-persistence/", [":adapter-out-persistence:compileJava", ":bootstrap:mysqlTest"], []),
    (r"^guard-tool/", [":guard-tool:test", ":bootstrap:mysqlTest"], []),
    (r"^bootstrap/src/main/resources/db/migration/", [":bootstrap:mysqlTest"],
     ["마이그레이션: .claude/skills/baton-go-flows/references/db-migration.md 점검"]),
    (r"^bootstrap/src/(main|test)/(?!resources/db/migration/)", [":bootstrap:test"], []),
    (r"^[^/]+/src/main/.*(ResolverQuota|Redis|/ratelimit/)", [":bootstrap:redisTest"], []),
    (r"^(compose\.yml|deploy/k8s/base/mysql-runtime-user-init\.sh)$", [":bootstrap:mysqlTest"], []),
    (r"^(build\.gradle|settings\.gradle|[^/]+/build\.gradle)$", [ARCH_TEST],
     ["의존성 버전 변경이면: ./gradlew --no-daemon --refresh-dependencies build && docker build --tag baton-go:local ."]),
    (r"^gradle/(verification-metadata\.xml|wrapper/)", [],
     ["의존성 검증: ./gradlew --no-daemon --refresh-dependencies build && docker build --tag baton-go:local ."]),
    (r"^(Dockerfile|\.dockerignore)$", [],
     ["이미지 빌드: docker build --tag baton-go:local ."]),
    (r"^deploy/k8s/", [],
     ["Kustomize: CI '필수 검증'의 'Kubernetes Kustomize strict schema 검증'·'배포 이미지 다이제스트 계약 검증' 단계를 로컬에서 실행 "
      "(kubeconform이 없으면 최소 kubectl kustomize <대상> >/dev/null)"]),
    (r"^deploy/prometheus/.*\.ya?ml$", [],
     ["Prometheus·Alertmanager: docs/RUNBOOK/prometheus-alerts.md#로컬ci-검증 (Java·DB 테스트 생략)"]),
    (r"^(tools/verify-webhooks\.py$|deploy/prometheus/alertmanager-|deploy/prometheus/.*-url\.example$)", [],
     ["웹훅: python3 tools/verify-webhooks.py --output <임시 디렉터리>/baton-go-webhooks (로컬 Docker 필요, 외부 전송 없음)"]),
    (r"^deploy/uptime/", [],
     ["외부 감시: CI의 '감시 시스템 중단 알림 설정·라우팅 검증' 단계와 docs/RUNBOOK/external-availability-monitoring.md 확인"]),
    (r"^\.github/", [],
     ["GitHub 설정: YAML 구문과 액션 고정 SHA·버전 주석 확인. 실제 실행은 원격 CI 결과로만 판단"]),
    (r"(^|/)(\.env|app\.env)\.example$", [],
     ["환경 변수 예시: application.yml·README의 변수 이름과 일치, 실제 비밀값·주소 미포함 확인"]),
    (r"\.py$", [],
     ["Python 구문: python3 -c 'import ast,sys; [ast.parse(open(f).read(), f) for f in sys.argv[1:]]' <파일...>"]),
    (r"\.md$", [],
     ["문서: python3 .claude/skills/baton-go-verify/scripts/check_doc_links.py (전체 md의 상대 링크·앵커 검사)"]),
]

MODULE_TASKS = {
    ":domain:test", ":application:test", ":adapter-in-web:test", ":adapter-out-external:test",
    ":guard-tool:test", ":bootstrap:test",
}


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def changed_files(base):
    entries = OrderedDict()
    for line in git("diff", "--name-status", "-M", base).splitlines():
        parts = line.split("\t")
        status = parts[0][0]
        for path in parts[1:]:
            entries[path] = status
    for path in git("ls-files", "--others", "--exclude-standard").splitlines():
        entries.setdefault(path, "A")
    return entries


def main():
    parser = argparse.ArgumentParser(description="변경 파일별 검증 명령 추천")
    parser.add_argument("--base", default="HEAD", help="비교 기준 리비전 (기본: HEAD, 미커밋·미추적 변경 포함)")
    args = parser.parse_args()

    try:
        os.chdir(git("rev-parse", "--show-toplevel").strip())
        files = changed_files(args.base)
    except subprocess.CalledProcessError as error:
        sys.stderr.write(error.stderr)
        return 2
    if not files:
        print("변경 파일 없음")
        return 0

    tasks = OrderedDict()
    notes = OrderedDict()
    unmatched = []
    for path, status in files.items():
        matched = False
        tags = integration_tags(path)
        for pattern, rule_tasks, rule_notes in RULES:
            if re.search(pattern, path):
                matched = True
                for task in rule_tasks:
                    if tags and task == ":bootstrap:test":
                        continue
                    tasks.setdefault(task, []).append(path)
                for note in rule_notes:
                    notes.setdefault(note, []).append(path)
        # 태그가 붙은 bootstrap 통합 테스트는 일반 test에서 제외되므로 해당 작업으로 실행한다.
        for tag in tags:
            tasks.setdefault(f":bootstrap:{tag}Test", []).append(path)
        # Java 파일 추가·삭제·이동은 패키지 구조 변경일 수 있다.
        if re.search(r"/src/main/java/.*\.java$", path) and status in "ADR":
            tasks.setdefault(ARCH_TEST, []).append(path)
            matched = True
        if not matched:
            unmatched.append(path)

    # 모듈 전체 bootstrap:test가 있으면 같은 모듈의 ArchitectureRulesTest 필터는 중복이다.
    if ":bootstrap:test" in tasks and ARCH_TEST in tasks:
        tasks[":bootstrap:test"].extend(tasks.pop(ARCH_TEST))

    print(f"기준: {args.base} / 변경 파일 {len(files)}개\n")
    print("공통: git diff --check")
    if tasks:
        print("\nGradle (한 번에 실행):")
        print("  ./gradlew --no-daemon " + " ".join(tasks))
        for task, paths in tasks.items():
            print(f"    {task}  ← {summarize(paths)}")
        if {":bootstrap:mysqlTest", ":bootstrap:redisTest"} & set(tasks):
            print("  * mysqlTest·redisTest는 Docker가 필요하다. 일반 test 성공으로 대신 보고하지 않는다.")
        if len(MODULE_TASKS & set(tasks)) >= 3:
            print("  * 여러 모듈에 걸친 변경이면 ./gradlew --no-daemon test 로 묶는 편이 단순할 수 있다.")
    if notes:
        print("\n추가 확인:")
        for note, paths in notes.items():
            print(f"  - {note}  ← {summarize(paths)}")
    if unmatched:
        print("\n규칙 없음 (내용 확인과 git diff --check만): " + summarize(unmatched, limit=10))
    return 0


def integration_tags(path):
    if not re.search(r"^bootstrap/src/test/.*\.java$", path):
        return []
    try:
        with open(path, encoding="utf-8") as source:
            text = source.read()
    except OSError:
        return []
    return [tag for tag in ("mysql", "redis") if f'@Tag("{tag}")' in text]


def summarize(paths, limit=3):
    unique = list(OrderedDict.fromkeys(paths))
    head = ", ".join(unique[:limit])
    return head + (f" 외 {len(unique) - limit}개" if len(unique) > limit else "")


if __name__ == "__main__":
    sys.exit(main())
