# 운영 요청 경로 비교 Phase 2: 콜드 스타트 결과 (2026-11-10)

계획은 [`docs/plans/2026-11-07-serving-path-cold-start.md`](../../../docs/plans/2026-11-07-serving-path-cold-start.md), 측정 기록은 [#185](https://github.com/BAECHAN/link-sphere_BE_NEW/issues/185)다. Phase 1(로컬 RIE 동작 비교) 결과는 [`2026-11-07.md`](./2026-11-07.md)다. MockMvc와 후보 B(serverless-java-container 2.1.7)를 같은 조건의 실험 함수에 올려 실제 SnapStart에서 콜드 스타트를 쟀다. 아래 수치는 모두 직접 측정이고, 콜드 행 원본은 같은 폴더의 [`2026-11-10-cold-start.csv`](./2026-11-10-cold-start.csv)다. 운영 함수 `link-sphere-api`와 운영 코드는 바꾸지 않았다.

**결론: 기준 4개를 모두 통과했다.** 콜드 p50은 MockMvc 1,746ms, 후보 B 1,713ms로 차이가 −33.6ms다(허용치 +500ms). 복원 실패·함수 오류·앱 500은 두 함수 모두 0건이다. 운영을 옮길지는 이 결과로 정하지 않는다. 따로 정한다.

## 조건

| 항목 | 값 |
| --- | --- |
| 대상 | `link-sphere-exp-mockmvc`: 기준 커밋 `a963e6b`(`poc/BASE`) 그대로. `link-sphere-exp-sjc`: 같은 커밋 + [`poc/candidate-b.patch`](../poc/candidate-b.patch) |
| 함수 설정 | 도쿄, `java17`, arm64, 2048MB, 120초, SnapStart(`PublishedVersions`), 버전 1, 별칭 `exp`. 메모리·런타임·아키텍처는 운영과 같다 |
| 코드 | 한 번의 발행 실행에서 두 JAR을 빌드했다(Corretto 17.0.21, 테스트 제외). `CodeSize`는 93,636,154 / 93,828,704바이트다. 빌드 단계가 MockMvc JAR에만 `MockMvc.class`, 후보 B JAR에만 `com/amazonaws/serverless/proxy/`가 있는지 확인했다 |
| 요청 | EventBridge `rate(20 minutes)`, 두 함수에 같은 `GET /api/post?size=5`(쿠키·토큰 없음, 쓰기 없음). 룰은 함수를 직접 부르고, 확인(`verify`)만 서명한 Function URL로 불렀다 |
| DB·외부 값 | 운영 DB에 운영 앱 계정·같은 풀러로 붙었다. 나머지 외부 키 5개는 더미, Firebase 파일이 없어 FCM은 꺼져 있다. 두 함수에 같다 |
| 판정 기간 | 2026-11-07T09:29:42Z(측정 시작 실행이 끝난 시각) ~ 2026-11-10T05:57:01Z(끝 `verify` 시작), 약 68.5시간 |
| 집계 | 조회 전용 키로 Logs Insights. `REPORT`에 `Restore Duration`이 있으면 콜드, 콜드 = `Restore Duration` + `Duration` |

콜드 스타트 정의는 AWS 문서를 따랐다. _"콜드 스타트 시간은 `Restore Duration`과 `Duration`의 합이다"_ (번역, [SnapStart 모니터링](https://docs.aws.amazon.com/lambda/latest/dg/snapstart-monitoring.html)).

infra 저장소 `Experiment` 워크플로 실행 기록: 스택 생성 `42347675680`, 발행 `42359315751`, 시작 확인 `42359822353`, 룰 켜기 `42359924872`, 끝 확인 `42765286407`, 룰 끄기 `42765856561`(2026-11-10T06:03Z, 룰 `DISABLED`).

## 판정

결과를 보기 전에 [#185](https://github.com/BAECHAN/link-sphere_BE_NEW/issues/185)에 적어 둔 기준이다. 표본(함수마다 콜드 30건 이상, 최소 24시간)은 첫 24시간에 함수마다 72건으로 채웠다. 끝내는 시각은 72시간 상한 전의 확인 시점(68.5시간)이다.

| 기준 | 결과 | 판정 |
| --- | --- | --- |
| ① 콜드 p50(후보 B) − 콜드 p50(MockMvc) ≤ 500ms(사용자 결정) | 1,712.55 − 1,746.13 = **−33.6ms** | 통과 |
| ② 복원 실패·함수 오류 0건(사용자 결정) | 두 함수 0건. `Status: error·timeout`, `Task timed out`, `SnapStart…Exception`으로 찾았다 | 통과 |
| ③ 측정 시작·끝 `verify` 통과(사용자 결정) | 두 번 모두 두 함수에서 `GET /api/post?size=5` 6회 연속 200, `GET /api/actuator/health` 200, `POST /api/auth/logout` 200과 `HttpOnly`·`Secure`·`SameSite=Lax` 쿠키 | 통과 |
| ④ 앱 500 로그 0건(Claude 제안) | 두 함수 0건. 운영 `App5xx` 필터와 같은 조건(`level = "ERROR"`이고 `GlobalExceptionHandler` 로거) | 통과 |

②④는 룰을 켠 시각(2026-11-07T09:29:27Z)부터 끝 `verify`가 끝난 뒤(2026-11-10T06:32Z)까지 두 로그 그룹 4,150행을 스캔한 값이다. 이 범위의 `REPORT`는 함수마다 214건(판정 기간 205 + 룰의 첫 호출 1 + 끝 `verify` 8)이다.

## 분포

| | MockMvc | 후보 B |
| --- | --- | --- |
| 판정 기간의 `REPORT` | 205건, 모두 콜드 | 205건, 모두 콜드 |
| 콜드 p50 / p90 / 최대 | 1,746 / 2,045 / 4,605ms | 1,713 / 2,030 / 4,758ms |
| 첫 콜드를 뺀 p50 | 1,746ms | 1,710ms |
| `Restore Duration` p50 / p90 | 694 / 907ms | 672 / 893ms |
| `Duration` p50 / p90 | 1,059 / 1,178ms | 1,051 / 1,186ms |
| 최대 메모리 p50 | 520MB | 514MB |

같은 룰이 두 함수를 동시에 부르므로 시각 순으로 짝을 지어 차이도 봤다(참고값, 판정에 쓰지 않음). 짝의 시각 차이는 최대 585ms다. 차이(후보 B − MockMvc)는 p10 −347ms, p50 −36ms, p90 +297ms이고, 205쌍 중 117쌍에서 후보 B가 빨랐다(같은 값 0쌍). 이 117/205를 부호 검정으로 직접 계산하면 양측 p가 0.0502다. 후보 B가 조금 빠를 수도 있다는 경계값이라, "차이가 없다"고도 "B가 빠르다"고도 단정하지 않는다. 차이가 있더라도 중앙값 기준 수십 ms라 허용치 500ms보다 훨씬 작다.

KST 날짜별 콜드 건수(함수마다 같다): 11-07 16, 11-08 72, 11-09 72, 11-10 45.

## 측정이 유효한가

| 확인 | 결과 |
| --- | --- |
| 룰 호출 수와 `REPORT` 수 | 판정 기간의 첫 호출 09:49:40Z부터 마지막 05:49:38Z까지 20분 간격이면 205회다. 두 함수 모두 `REPORT` 205건으로 같다 |
| 콜드 비율 | 205/205로 100%다. 20분 유휴가 콜드를 보장한다는 근거가 없어 첫날 따로 셌고, 끝까지 웜 호출이 섞이지 않았다 |
| 판정 기간 밖의 호출 | 룰을 켠 직후(09:29:36Z) 두 함수가 한 번씩 웜으로 불렸다. 룰의 첫 호출로 보이며(시각으로 판단), 80초 전 `verify`의 실행 환경이 남아 있었다. 판정 기간 밖이고 판정은 콜드 행만 쓴다 |
| 두 JAR이 다른 구조인가 | 위 "조건"의 `CodeSize`와 빌드 단계의 클래스 확인 |

## 그 밖에 본 것

- **끝 `verify`에서 후보 B는 콜드가 2번이었다.** 연속 GET 6회 중 첫 응답이 끝나고 약 0.5초 뒤 온 두 번째 요청이 새 실행 환경으로 갔다(`REPORT` 시각과 `Restore Duration`으로 판단). 나머지 4회는 웜이었고 모두 200이라 ③에는 영향이 없다. 시작 `verify`에서는 두 함수 모두 첫 1회만 콜드였다. 1건이라 원인은 확인하지 않았고, 후보 B에서만 생기는 일인지도 모른다.
- **버전 발행 뒤 첫 콜드가 가장 컸다.** MockMvc 4,605ms, 후보 B 4,758ms. 빼도 p50은 위 표처럼 거의 같다.
- **`Set-Cookie`의 `Expires` 날짜 형식이 다르다.** 로그아웃 응답에서 MockMvc는 `Thu, 1 Jan 1970`, 후보 B는 `Thu, 01 Jan 1970`이다. `Max-Age=0`은 같다. 끝 `verify`는 글 목록과 로그아웃 응답의 `Content-Type`·`Vary`·`Strict-Transport-Security`·`Cache-Control`·`X-Content-Type-Options`·`X-Frame-Options`·`Set-Cookie`를 두 함수끼리 비교했고(요청 ID·날짜는 뺌), 이 7개 안에서 차이는 이것뿐이었다. 목록 밖 헤더가 한쪽에만 있는지는 보지 않았다. 브라우저가 이 쿠키를 지우는지는 확인하지 않았다. 옮기기로 하면 확인할 항목이다.
- **`curl`로 잰 왕복 시간**은 1회씩이라 비교하지 않는다. 콜드 첫 호출이 약 2.2~2.7초, 웜이 약 0.46초였다.
- **절대값은 운영과 다를 수 있다.** _"SnapStart는 대규모 호출에서 가장 잘 동작한다. 드물게 호출되는 함수는 같은 성능 개선을 얻지 못할 수 있다"_ (번역, [SnapStart 성능](https://docs.aws.amazon.com/lambda/latest/dg/snapstart-best-practices.html)). 실험 함수는 20분에 한 번만 불렸다. 그래서 판정은 같은 조건의 두 함수 차이로만 했다. [`PERFORMANCE.md`](../../../docs/PERFORMANCE.md)의 운영 콜드 p50(2026-07-25)은 코드·트래픽·측정 방식이 달라 이 값과 비교하지 않는다.

## 이번에 알 수 없는 것

- **CloudFront를 지난 값:** 실험 함수에는 CloudFront가 없다.
- **내부 작업 6종의 실제 동작:** 실험 함수 역할에 메일·S3·자기 호출 권한이 없어 돌리지 않았다. Phase 1에서 분기 도달까지만 확인했다.
- **운영 트래픽 아래의 콜드 스타트:** 위 "절대값" 항목과 같다.
- **Spring Boot 4로 옮긴 뒤:** 후보 B의 2.x는 Boot 3.x 라인이고, Boot 4에는 라이브러리 3.x 라인이 필요하다([저장소 README](https://github.com/aws/serverless-java-container), [`2026-11-07.md`](./2026-11-07.md) "후보별로 새로 생기는 것"에서 재인용). 3.x는 이번에 재지 않았다.

옮기기로 하면 Phase 1의 남은 차이(`search=a=b`의 검색어 무시, `/api/post/`의 200, 요청마다 IP가 담긴 접속 로그)와 위 `Expires` 형식, BE `.claude/CLAUDE.md`의 MockMvc 관련 규칙을 함께 다루는 계획을 따로 세운다.

## 재현

CSV는 판정 기간의 콜드 행 전체다(함수마다 205행, 시각·대상·수치만). 두 로그 그룹에서 판정 기간(`--start-time 1794043782 --end-time 1794290221`)의 `REPORT` 줄 원문을 받아 로컬에서 나눴다. Logs Insights에서 `parse` 뒤에 같은 이름을 `fields`로 다시 쓰면 오류가 나서 원문을 받았다.

```
filter @type = "REPORT" | fields @timestamp, @log, @message | sort @timestamp asc
```

`REPORT` 줄은 `Duration: 993.17 ms  Billed Duration: 1011 ms  Memory Size: 2048 MB  Max Memory Used: 515 MB  Restore Duration: 634.22 ms  Billed Restore Duration: 17 ms` 꼴이다. 여기서 `Restore Duration`이 있는 줄만 남기고 `Restore Duration`·`Duration`·`Billed Duration`·`Billed Restore Duration`·`Max Memory Used`를 열로 옮겼다. `cold_ms`는 `restore_ms + duration_ms`다. 계정 ID가 든 `@log`는 대상 이름(`mockmvc`·`sjc`)으로 바꿨다.

위 표와 본문의 수치는 CSV에서 이렇게 다시 계산한다(Python 3 표준 라이브러리만 쓴다).

```bash
python3 - scripts/serving-path-experiment/results/2026-11-10-cold-start.csv <<'EOF'
import csv, statistics as st, sys
from collections import Counter
from datetime import datetime, timedelta
from math import comb
rows = list(csv.DictReader(open(sys.argv[1])))
p = lambda xs, k: st.quantiles(xs, n=100, method='inclusive')[k - 1]
col = lambda rs, c: [float(r[c]) for r in rs]
by = {t: [r for r in rows if r['target'] == t] for t in ('mockmvc', 'sjc')}
for t, rs in by.items():
    cold, restore, dur = col(rs, 'cold_ms'), col(rs, 'restore_ms'), col(rs, 'duration_ms')
    print(t, len(rs), 'cold p50/p90/max', st.median(cold), round(p(cold, 90)), max(cold), 'p50 without first', round(st.median(cold[1:])),
          'restore p50/p90', round(st.median(restore)), round(p(restore, 90)), 'duration p50/p90', round(st.median(dur)), round(p(dur, 90)),
          'memory p50', st.median(col(rs, 'max_memory_mb')))
diff = [float(b['cold_ms']) - float(a['cold_ms']) for a, b in zip(by['mockmvc'], by['sjc'])]
faster = sum(d < 0 for d in diff)
print('p50 diff', round(st.median(col(by['sjc'], 'cold_ms')) - st.median(col(by['mockmvc'], 'cold_ms')), 2))
print('pair diff p10/p50/p90', round(p(diff, 10)), round(st.median(diff)), round(p(diff, 90)), 'sjc faster', faster, 'ties', sum(d == 0 for d in diff))
print('sign test two-sided p', round(2 * sum(comb(len(diff), i) for i in range(max(faster, len(diff) - faster), len(diff) + 1)) / 2 ** len(diff), 4))
kst = lambda r: (datetime.fromisoformat(r['timestamp_utc'].replace('Z', '+00:00')) + timedelta(hours=9)).date().isoformat()
print('cold per KST day', dict(Counter(kst(r) for r in by['mockmvc'])))
EOF
```
