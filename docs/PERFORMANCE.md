# Link-Sphere BE — 성능 (Lambda 콜드스타트)

이 문서는 **"왜 느렸고, 무엇을 근거로 무엇을 바꿨는지"** 를 남긴다.
배포 절차는 [DEPLOY.md](./DEPLOY.md), 무엇이 바뀌었는지는 [CHANGELOG.md](../CHANGELOG.md)를 참고.

---

## 1. 문제

첫 화면 로딩이 3~4초 걸린다는 제보에서 출발했다. 측정해 보니 원인은 **Lambda 콜드스타트**였다.

---

## 2. 측정 기준선 (개선 전)

- **측정일**: 2026-07-25
- **대상**: `link-sphere-api:prod` (ap-northeast-1, java17, arm64, 1024MB, SnapStart On)
- **표본**: 최근 48시간 CloudWatch `REPORT` 로그 **324건**

| 구간 | p50 | p90 | max |
| ---- | --- | --- | --- |
| **콜드 총합** (restore + 첫 요청) | **3,523ms** | **7,099ms** | 9,914ms |
| ├ SnapStart restore | 659ms | 847ms | 926ms |
| └ restore 이후 첫 요청 | **2,906ms** | 6,338ms | 9,203ms |
| **웜 요청** | 148ms | 400ms | — |

- **콜드 비율 22%** (72 / 324)
- `Max Memory Used` 460MB / 1024MB → 메모리는 남는다

---

## 3. 원인 분석

**SnapStart 자체는 정상 동작했다.** restore는 659ms로 문제가 아니었다.
진짜 병목은 **restore 이후 첫 요청 2.9초**다.

`LambdaHandler`의 `companion object init`은 Spring 컨텍스트를 띄우고 `MockMvc`를 만드는 데서 끝났다.
그래서 아래 초기화가 전부 **스냅샷 바깥**에 남아 복원 후 첫 요청이 한꺼번에 뒤집어썼다.

- `DispatcherServlet` 최초 초기화 (`Initializing Servlet 'dispatcherServlet'`)
- Spring Security 필터 체인 첫 통과
- Hibernate 메타모델·쿼리플랜 캐시 생성
- HikariCP 실제 커넥션 확보 (Supabase 풀러까지 왕복)
- JVM이 해당 코드 경로를 인터프리터로 실행 (JIT 미적용)

여기에 1024MB(≈0.58 vCPU)라 CPU 바운드인 클래스 로딩·JIT이 더 느렸다.

---

## 4. 적용한 최적화

### 4.1 SnapStart 스냅샷에 워밍업 굽기 (핵심)

`LambdaHandler.warmUp()` — `companion object init`은 체크포인트 **이전**에 실행되므로,
여기서 실제 요청을 흘려보내면 위 초기화 비용이 전부 스냅샷에 포함된다.

- `GET /actuator/health` → DispatcherServlet, HandlerMapping, Security 필터 체인, Jackson
- `GET /common/category-options` → 추가로 Hibernate 쿼리플랜, HikariCP 커넥션
- JIT를 인터프리터 밖으로 밀기 위해 3회 반복

제약:
- 읽기 전용·`permitAll` 엔드포인트만 사용한다 (부작용 방지)
- 실패해도 부팅은 계속한다 — 배포 시점에 DB가 닿지 않아도 Lambda는 기동되어야 한다
- `DataSourceCracHook.beforeCheckpoint`의 `suspendPool()`은 체크포인트 시점에 호출되므로
  init 단계의 DB 워밍업과 충돌하지 않는다. **순서를 바꾸지 말 것**

### 4.2 메모리 1024 → 2048MB

Lambda는 메모리에 비례해 vCPU를 준다(1024MB ≈ 0.58 vCPU → 2048MB ≈ 1.15 vCPU).
첫 요청은 CPU 바운드라 거의 선형으로 줄어든다. 메모리 자체가 목적이 아니다(사용량 460MB).

### 4.3 EventBridge 워밍 핑 (5분)

콜드 비율 22%를 구조적으로 낮춘다. 다만 **컨테이너 1개만** 유지하므로 동시 요청 초과분은
여전히 콜드다 — 그래서 4.1이 여전히 핵심이다. 생성 커맨드는 [DEPLOY.md](./DEPLOY.md) 참고.

### 4.4 기동 설정

- `spring.jpa.open-in-view: false` — 요청당 커넥션을 뷰 렌더까지 붙들지 않음 (기본값 true + 기동 경고)
- `spring.jmx.enabled: false` — MBean 등록 단계 생략

### 4.5 만료 토큰 로그 등급

`JwtAuthenticationFilter` / `JwtTokenProvider`가 만료 토큰마다 `ExpiredJwtException`
**전체 스택트레이스**를 ERROR로 남기고 있었다. 만료는 FE가 refresh로 복구하는 정상 흐름이라
스택 생성 + 로그 쓰기가 낭비였다. WARN 한 줄로 낮췄다(위변조·서명 불일치는 ERROR 유지).

---

## 5. 개선 후 수치

> **배포 후 아래 재측정 절차로 채운다.** 비어 있으면 검증이 끝나지 않은 것이다.

| 구간 | p50 | p90 | 기준선 대비 |
| ---- | --- | --- | ----------- |
| 콜드 총합 | _(미측정)_ | | 기준선 3,523ms |
| ├ SnapStart restore | _(미측정)_ | | 기준선 659ms |
| └ restore 이후 첫 요청 | _(미측정)_ | | 기준선 2,906ms |
| 웜 요청 | _(미측정)_ | | 기준선 148ms |
| 콜드 비율 | _(미측정)_ | | 기준선 22% |

**합격 기준: 콜드 총합 p50 < 1,500ms**, 웜 p50은 148ms 수준 유지.

---

## 6. 재측정 방법

새 버전을 `publish-version` 하면 스냅샷이 새로 만들어지므로 첫 호출은 반드시 콜드다.

```bash
aws logs filter-log-events \
  --log-group-name /aws/lambda/link-sphere-api \
  --region ap-northeast-1 \
  --start-time $(( ($(date +%s) - 172800) * 1000 )) \
  --filter-pattern 'REPORT' --max-items 600 \
  --query 'events[].message' --output text > rep.txt
```

**콜드/웜 분리 기준**: `REPORT` 블록에 `Restore Duration` 필드가 있으면 콜드, 없으면 웜.
콜드 총합 = `Restore Duration` + `Duration`.

```bash
python3 - <<'EOF'
import re, statistics
t = open("rep.txt").read().replace('\t', '\n')
cold, warm, rest = [], [], []
for b in re.split(r'(?=REPORT RequestId)', t):
    if not b.startswith('REPORT'):
        continue
    m = re.search(r'Duration: ([\d.]+) ms', b)
    if not m:
        continue
    d = float(m.group(1))
    r = re.search(r'Restore Duration: ([\d.]+) ms', b)
    if r:
        cold.append(d); rest.append(float(r.group(1)))
    else:
        warm.append(d)

def s(name, a):
    if not a:
        print(name, "none"); return
    a = sorted(a)
    p90 = a[max(0, int(len(a) * .9) - 1)]
    print(f"{name:24} n={len(a):3} p50={statistics.median(a):7.0f} p90={p90:7.0f} max={a[-1]:7.0f} ms")

s("COLD 1st-request", cold)
s("  SnapStart restore", rest)
s("WARM", warm)
if cold:
    s("COLD TOTAL", [c + r for c, r in zip(cold, rest)])
    print(f"cold rate: {100 * len(cold) / (len(cold) + len(warm)):.0f}%")
EOF
```

콜드 비율은 트래픽 패턴에 좌우되므로 **워밍 핑 적용 후 24시간 지나서** 다시 본다.

---

## 7. 비용 분석

> 이 계정은 **프리티어가 아니라 실과금 상태**다. 무료 한도를 전제하지 않고 계산한다.

**측정값 (30일)**: 호출 1,852회, 총 실행 1,555 GB-초, Logs 수집 5.9MB, S3 배포 버킷 3.31GB / jar 39개
**단가 (ap-northeast-1, arm64)**: 실행 `$0.0000133334/GB-초`, 요청 `$0.20/100만`, S3 Standard `약 $0.025/GB-월`

| 항목 | 현재 | 변경 후 | 증감 |
| ---- | ---- | ------- | ---- |
| Lambda 컴퓨트 | $0.021 | $0.036 ~ $0.076 | +$0.015 ~ +$0.055 |
| Lambda 요청 | $0.0004 | $0.0021 | +$0.002 |
| EventBridge 워밍핑 | — | $0 ~ $0.011 | +$0.011 이하 |
| SnapStart | $0 | $0 | 0 (Java는 추가 과금 없음) |
| CloudWatch Logs | 약 $0.005 | 약간 감소 | 소폭 − |
| **S3 배포 버킷** | **$0.083** | **$0.013 ~ $0.025** | **−$0.06 ~ −$0.07** |
| **합계** | **약 $0.11/월** | **약 $0.05 ~ $0.11/월** | **≈ 0 또는 감소** |

- **메모리 2배가 요금 2배가 아니다.** Lambda는 `GB × 초` 과금인데 메모리를 올리면 vCPU도
  올라가 실행 시간이 줄어 상쇄된다 → GB ×2, 초 ×0.6 → 실질 거의 중립.
- **SnapStart는 Java 런타임에 추가 과금이 없다**
  (AWS 요금 페이지: "SnapStart pricing does not apply to supported Java managed runtimes").
- **가장 큰 단일 항목은 S3 배포 버킷이었다.** 배포마다 85MB jar가 정리 없이 쌓였다
  → 수명 주기 규칙은 [DEPLOY.md](./DEPLOY.md) 참고.
- `link-sphere-user` IAM에는 `ce:GetCostAndUsage` 권한이 없어 CLI로 실제 청구액을 못 본다.
  위 수치는 **사용량 실측 × 공시 단가**이므로 콘솔 Billing에서 한 번 대조할 것.

---

## 8. FE 쪽 병목 (참고)

BE만 고쳐서는 체감이 다 낫지 않았다. FE가 콜드스타트를 직렬로 증폭하고 있었다.

`AuthProvider`가 라우터 전체를 `POST /auth/refresh` 뒤에 블로킹했고, auth store가 persist가
아니라 **비로그인 방문자도 매번** 이 호출을 했다. 콜드 람다를 때리면
`3.5초 풀스크린 스피너 → 라우트 청크 다운로드 → 목록 조회` 순으로 완전 직렬이었다.

FE 대응은 링크스피어 FE 레포의 `docs/DECISIONS.md`(2026-07-25 항목) 참고.
