"""PR CI(ci.yml check 잡의 "커버리지 보고" 스텝)가 쓰는 커버리지 보고 스크립트. 두 가지 모드가 있다.

--targets <기준 커밋>
    이 PR이 바꾼 src/main/kotlin/**/*.kt를 한 줄에 하나씩 출력한다.
--report <JaCoCo XML> <대상 목록 파일> <기준 커밋>
    대상 파일의 커버리지와, 이 PR이 바꾼 줄 중 테스트가 실행하지 않은 줄을 GitHub Step Summary·PR 댓글용
    마크다운으로 출력한다.

머지를 막지 않는 정보용 보고다(FE scripts/mutation-report.js와 같은 모양, docs/CI-CHECK-GATE.md "커버리지 보고").
러너에 깔린 python3로 돌도록 표준 라이브러리만 쓴다.
"""

import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE_ROOT = "src/main/kotlin/"
HEADING = "## 커버리지 (정보용 — 머지를 막지 않음)"
# 보고 전체 길이 상한. 넘으면 파일별 목록을 빼고 표만 남긴다(PR 댓글 한도 65,536자에 여유를 둔 값, FE와 같다).
MAX_REPORT_LENGTH = 60000
HUNK_HEADER = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def list_targets(base):
    # 워킹트리와 비교한다 - CI에서는 PR 머지 커밋이 체크아웃돼 있어 PR의 변경과 같다(FE mutation-report.js와 같은 방식)
    changed = git("diff", "--name-only", "--diff-filter=d", base, "--", SOURCE_ROOT).splitlines()
    return sorted(file for file in changed if file.endswith(".kt"))


def changed_lines(base, file):
    """이 PR에서 새로 생기거나 바뀐 줄 번호(새 파일 기준)."""
    lines = set()

    for row in git("diff", "-U0", base, "--", file).splitlines():
        match = HUNK_HEADER.match(row)

        if match:
            start = int(match.group(1))
            count = int(match.group(2) or 1)
            lines.update(range(start, start + count))

    return lines


def load_report(xml_path):
    """JaCoCo XML을 {"패키지 경로/파일명": {줄 번호: (놓친 명령, 실행된 명령, 놓친 갈래, 지난 갈래)}}로 읽는다."""
    root = ET.parse(xml_path).getroot()
    files = {}

    for package in root.iter("package"):
        for source in package.iter("sourcefile"):
            files[f"{package.get('name')}/{source.get('name')}"] = {
                int(line.get("nr")): tuple(int(line.get(key)) for key in ("mi", "ci", "mb", "cb"))
                for line in source.iter("line")
            }

    return files


def find_lines(files, target):
    # JaCoCo는 선언된 패키지로 묶는다. Kotlin은 패키지와 폴더가 달라도 되므로 경로로 못 찾으면 파일 이름이 하나뿐일 때만 쓴다
    key = target.removeprefix(SOURCE_ROOT)

    if key in files:
        return files[key]

    same_name = [lines for path, lines in files.items() if path.rsplit("/", 1)[-1] == key.rsplit("/", 1)[-1]]
    return same_name[0] if len(same_name) == 1 else None


def percent(covered, total):
    return f"{covered / total * 100:.1f}%" if total else "-"


def compress(numbers):
    """[3, 4, 5, 9] -> "3–5, 9" """
    ranges = []

    for number in sorted(numbers):
        if ranges and number == ranges[-1][1] + 1:
            ranges[-1][1] = number
        else:
            ranges.append([number, number])

    return ", ".join(str(a) if a == b else f"{a}–{b}" for a, b in ranges)


def file_section(target, lines, changed):
    executable_changed = changed & lines.keys()
    missed = {nr for nr in executable_changed if lines[nr][1] == 0}
    partial = {nr: lines[nr] for nr in executable_changed if lines[nr][1] > 0 and lines[nr][2] > 0}
    covered_lines = sum(1 for counts in lines.values() if counts[1] > 0)
    missed_branches = sum(counts[2] for counts in lines.values())
    covered_branches = sum(counts[3] for counts in lines.values())

    row = (
        f"| `{target}` | {percent(covered_lines, len(lines))} | {percent(covered_branches, missed_branches + covered_branches)} "
        f"| {len(executable_changed)} | {len(missed)} | {len(partial)} |"
    )

    if not missed and not partial:
        return row, ""

    items = []

    if missed:
        items.append(f"- 안 닿음: {compress(missed)}줄")

    if partial:
        branches = ", ".join(f"{nr}줄({cb}/{mb + cb})" for nr, (_, _, mb, cb) in sorted(partial.items()))
        items.append(f"- 갈래 일부만 지남: {branches}")

    summary = f"<code>{target}</code> — 바꾼 줄 중 안 닿음 {len(missed)}줄, 갈래 일부만 지남 {len(partial)}줄"
    return row, f"<details><summary>{summary}</summary>\n\n" + "\n".join(items) + "\n\n</details>"


def build_report(xml_path, targets_path, base):
    targets = [line for line in Path(targets_path).read_text().splitlines() if line] if Path(targets_path).exists() else []

    if not targets:
        return f"{HEADING}\n\n이 PR에는 대상이 없다. 대상은 바뀐 `{SOURCE_ROOT}**/*.kt`다."

    if not Path(xml_path).exists():
        listed = "\n".join(f"- `{target}`" for target in targets)
        return f"{HEADING}\n\n커버리지 리포트가 없어 보고하지 못했다(리포트 생성 실패). job 로그를 확인한다.\n\n대상:\n\n{listed}"

    files = load_report(xml_path)
    rows = []
    details = []

    for target in targets:
        lines = find_lines(files, target)

        if not lines:
            # 인터페이스·상수·애너테이션만 있는 파일은 실행할 줄이 없어 리포트에 없다
            rows.append(f"| `{target}` | - | - | 0 | - | - |")
            continue

        row, detail = file_section(target, lines, changed_lines(base, target))
        rows.append(row)

        if detail:
            details.append(detail)

    sections = [
        HEADING,
        "이 PR이 바꾼 Kotlin 파일의 커버리지를 단위·통합 테스트를 합쳐 쟀다. **안 닿음**은 이 PR이 바꾼 줄 중 그 줄을 실행하는 "
        "테스트가 없는 줄이고, **갈래 일부만 지남**은 `if`·`when` 등의 갈래 중 일부만 테스트가 지난 줄이다. "
        "비율은 목표가 아니라 테스트가 없는 곳을 찾는 단서다.",
        "| 파일 | line(파일 전체) | branch(파일 전체) | 바꾼 실행 줄 | 그중 안 닿음 | 갈래 일부만 지남 |\n"
        "| --- | --- | --- | --- | --- | --- |\n" + "\n".join(rows),
        "\n\n".join(details),
        "로컬 재현: `./gradlew test integrationTest coverageReport` → `build/reports/jacoco/coverageReport/html/index.html`",
    ]
    report = "\n\n".join(section for section in sections if section)

    if len(report) <= MAX_REPORT_LENGTH:
        return report

    sections[3] = "파일별 목록은 PR 댓글 길이 한도를 넘어 뺐다. 아래 로컬 재현 명령으로 HTML 리포트를 본다."
    return "\n\n".join(sections)


def main():
    mode, *args = sys.argv[1:] or [""]

    if mode == "--targets" and len(args) == 1:
        targets = list_targets(args[0])

        if targets:
            print("\n".join(targets))

        return

    if mode == "--report" and len(args) == 3:
        print(build_report(*args))
        return

    print("사용법: python3 scripts/coverage-report.py --targets <기준 커밋>", file=sys.stderr)
    print("        python3 scripts/coverage-report.py --report <JaCoCo XML> <대상 목록 파일> <기준 커밋>", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()
