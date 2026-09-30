# Lambda 배포 설정 롤백 런북

> 마지막 검토: 2026-09-30 (즉시 검증의 CloudFront 경유 예시 URL을 커스텀 도메인
> `linksphere.click`으로 교체)

2026-07-25 502 장애 대응 과정에서 작성된 변경 전 상태 스냅샷 겸, 이후에도
prod alias를 안전 버전으로 되돌릴 때 계속 참고하는 롤백 절차서다.

> ⚠️ **alias를 옮기기 전에 반드시 읽을 것**: Lambda 버전은 설정(메모리 등)뿐
> 아니라 **애플리케이션 코드 전체의 스냅샷**이다. 2026-09-28 PR #42(JWT 폐지 →
> 서버 관리 세션)부터 인증 방식이 근본적으로 바뀌었고, 그 이후로도 거의 매
> PR마다 새 버전이 배포되고 있다(현재 prod는 v122). **PR #42 이전 버전(커밋
> `d9afef9` 이전, 2026-09-28 22:13 KST 이전에 배포된 모든 버전 — 아래
> "알려진 안전 지점" 표의 v39/v43/v46 포함)으로 롤백하면 FE가 더 이상
> 호환되지 않는 JWT 기반 인증 코드로 돌아가 로그인이 전부 깨진다.** 아래
> "알려진 안전 지점" 표는 **2026-07-25 장애 당시의 메모리/LWA/워밍업 설정
> 롤백 전용**이며 그 이후 추가된 기능(세션 인증·레이트리밋·이메일 인증·탈퇴
> 유예·OAC 등)과는 무관하다 — 지금 일반적인 장애 대응으로 alias를 옮길
> 때는 이 표를 쓰지 말고 아래 "현재 기준 안전한 롤백 지점 찾는 법"을 따른다.

계정 `185353921021` / 리전 `ap-northeast-1` / 함수 `link-sphere-api`

## 변경 전 상태

| 항목 | 변경 전 값 |
| ---- | ---------- |
| `$LATEST` MemorySize | **1024** |
| `$LATEST` Timeout | 120 |
| Runtime / Arch | java17 / arm64 |
| SnapStart ApplyOn | PublishedVersions |
| `prod` alias → 버전 | **39** |
| 버전 39 MemorySize | 1024 |
| 버전 39 SnapStart | On |
| S3 lifecycle | **없음** (NoSuchLifecycleConfiguration) |
| S3 버전 관리 | 비활성 (Status 없음) |
| EventBridge 룰 | **없음** (ListRules 빈 배열) |
| AWS CLI 기본 리전 | `northeast-1` (오타 — `ap-` 누락, `aws configure set region ap-northeast-1`로 수정함) |

## 최종 상태 (2026-07-25 장애 대응 완료 시점 — 그 뒤 alias는 계속 새 버전으로 옮겨졌다. "현재" prod 버전은 위 "현재 기준 안전한 롤백 지점 찾는 법"으로 그때그때 확인)

| 항목 | 변경 전 | 장애 대응 완료 시점 |
| ---- | ------- | -------- |
| `prod` alias | 39 | 46 |
| LWA 레이어 | `LambdaAdapterLayerArm64:24` 부착 | **제거됨** |
| 워밍업 코드 | 없음 | **적용됨** (경로 `/common/category-option`, 2xx 검증) |
| 메모리 | 1024 | **2048** |
| EventBridge 워밍 핑 | 없음 | **적용됨** (`rate(5 minutes)`, `prod` 대상) |
| S3 수명 주기 | 없음 | **적용됨** (`deployments/` 30일 만료) |
| AWS CLI 기본 리전 | `northeast-1` (오타) | `ap-northeast-1` |

> 중간 경과: v40(워밍업+2048) 배포 → 502 장애 → v39 롤백 → v42(워밍업 철회) →
> v43(LWA 제거) → v44/v45(워밍업 재투입) → **v46(메모리 2048)**.
> 자세한 경위는 [PERFORMANCE.md](./PERFORMANCE.md) 5장.

### 알려진 안전 지점 (2026-07-25 장애 대응 당시, 메모리/LWA/워밍업 전용)

> ⚠️ 아래 3개 버전은 **전부 PR #42(JWT 폐지) 이전**이다. 지금 이 버전들로
> alias를 옮기면 메모리·LWA·워밍업 설정은 표대로 복원되지만, 그 대가로
> 세션 인증·레이트리밋·이메일 인증·탈퇴 유예 등 그 이후 기능이 전부 통째로
> 사라지고 FE와 인증 방식이 안 맞아 로그인이 깨진다. **2026-09-28 이후
> 벌어진 문제의 롤백에는 이 표를 쓰지 않는다** — 아래 "현재 기준 안전한
> 롤백 지점 찾는 법" 절 참고.

| 버전 | 구성 | 비고 |
| ---- | ---- | ---- |
| v46 | LWA 없음 + 워밍업 + 2048MB | 2026-07-25 장애 대응 완료 시점(당시 prod). 콜드 첫 요청이 가장 빠름 |
| v43 | LWA 없음 + 워밍업 없음 | 워밍업을 의심할 때 되돌릴 지점(장애 당시 기준) |
| v39 | LWA 있음 + 워밍업 없음 | 장애 이전 원본 상태 |

### 현재 기준 안전한 롤백 지점 찾는 법 (2026-09-29 이후)

이제는 버전마다 코드 자체가 다르므로 "항상 안전한 고정 버전"을 표로
박아두지 않는다(이 문서가 몇 달째 v46을 "현재 prod"라고 잘못 가리키고
있었던 게 바로 이 방식의 실패 사례다). 대신 그때그때 다음 순서로 확인한다:

```bash
# 1. 최신 버전·prod alias 확인 (이 문서를 읽는 시점에 이미 더 올라가 있을 수 있음)
aws lambda list-aliases --function-name link-sphere-api
aws lambda list-versions-by-function --function-name link-sphere-api \
  --query 'Versions[-15:].{Ver:Version,Mod:LastModified}' --output table

# 2. 최근 병합된 PR과 그 배포 시각을 대조 (deploy.yml은 main push마다 새 버전을 publish)
git log --format='%H %ai %s' origin/main -20
```

- 후보 버전이 커밋 `d9afef9`(PR #42, 2026-09-28 22:13 KST) **이후**에
  배포됐는지 반드시 확인한다. 이전이면 절대 후보에서 제외한다.
- 후보를 정했으면 alias를 옮기기 전에 아래 "주의" 절의 연속 호출 검증을 거친다.
- 메모리·LWA·워밍업 자체가 의심되는 경우(2026-07-25와 같은 유형의 장애)에만
  위 "알려진 안전 지점" 표의 구성값(2048MB·LWA 없음·워밍업 있음)을 참고
  자료로 쓰고, 실제로 옮길 버전은 반드시 #42 이후 최신에 가까운 것을 고른다.

### LWA 레이어 복원 (권장하지 않음)

```bash
aws lambda update-function-configuration --function-name link-sphere-api \
  --layers arn:aws:lambda:ap-northeast-1:753240598075:layer:LambdaAdapterLayerArm64:24
aws lambda wait function-updated --function-name link-sphere-api
aws lambda publish-version --function-name link-sphere-api
# 발행된 버전으로 alias 이동
```

**되돌리기 전에 반드시 확인할 것**: 이 레이어가 2026-07-25 502 장애의 직접 원인이었다.
복원하면 **현재 적용된 워밍업이 다시 깨진다**(워밍업은 LWA가 없는 상태를 전제로 한다).
레이어를 복원하려면 워밍업(`LambdaHandler.warmUp()`)도 함께 제거해야 한다.

## 롤백 커맨드

**가장 빠른 롤백은 alias 이동이다.** 버전은 코드와 설정(메모리 포함)을 함께 고정하므로,
alias만 되돌리면 메모리까지 한 번에 돌아간다.

```bash
F=link-sphere-api   # 리전은 CLI 기본값(ap-northeast-1)을 쓴다

# 1. 즉시 롤백 — <버전>은 위 "현재 기준 안전한 롤백 지점 찾는 법"으로 확인한 값.
#    절대 PR #42(2026-09-28 22:13 KST, 커밋 d9afef9) 이전 버전을 넣지 않는다.
aws lambda update-alias --function-name $F --name prod --function-version <버전>
```

아래는 개별 설정을 되돌릴 때만 쓴다.

```bash
# 2. 메모리 되돌리기 ($LATEST 기준. 반영하려면 publish-version 후 alias 이동 필요)
aws lambda update-function-configuration --function-name $F --memory-size 1024

# 3. S3 수명 주기 제거
aws s3api delete-bucket-lifecycle --bucket link-sphere-lambda-deploy

# 4. EventBridge 워밍핑 제거
#    주의: 인라인 정책 ops-warmup-and-diagnostics를 회수했다면 먼저 다시 붙여야 실행된다
aws events remove-targets --rule link-sphere-api-warmup --ids warmup
aws events delete-rule --name link-sphere-api-warmup
aws lambda remove-permission --function-name $F --qualifier prod \
  --statement-id EventBridgeWarmup
```

## Function URL AuthType / CloudFront OAC 롤백 (2026-09-29 Phase 7, 적용 완료)

Function URL을 `--auth-type AWS_IAM` + CloudFront OAC로 잠그는 작업(위 alias/메모리
롤백과는 무관한, 별도 변경 축 — `docs/plans/2026-09-28-auth-hardening.md` Phase 7,
`docs/DEPLOY.md` §5-1 참고)은 **이미 적용 완료**됐다(현재 Function URL AuthType은
`AWS_IAM`, OAC `link-sphere-api-lambda-oac` 연결됨). 이 절은 그 상태에서 CloudFront
경유 요청이 401/403/500 등으로 실패해 긴급히 공개 상태로 되돌려야 할 때만 쓴다.

```bash
aws lambda update-function-url-config --function-name link-sphere-api \
  --qualifier prod --auth-type NONE
```

이 명령은 CloudFront 오리진에 OAC가 여전히 연결돼 있어도 안전하다 — Function URL이
`NONE`이면 CloudFront가 보낸 SigV4 서명을 아예 검사하지 않고 무시하기 때문이다.
`add-permission`으로 부여한 CloudFront invoke 권한, `create-origin-access-control`로
만든 OAC 자체, 배포 설정에 연결한 `OriginAccessControlId`는 그대로 둬도 무해하다 —
되돌리는 데 필요한 변경은 오직 `AuthType`뿐이다.

**기존 공개 권한을 이미 제거한 뒤라면** 위 롤백만으론 부족하다 — `AuthType: NONE`은
익명(`principal: "*"`) 호출이 허용되려면 별도로 `lambda:InvokeFunctionUrl` 권한이
부여돼 있어야 한다는 뜻일 뿐, 그 권한 자체를 자동으로 주지는 않는다. CloudFront는
OAC가 오리진에 연결돼 있는 한 `AuthType` 설정과 무관하게 계속 SigV4로 서명해서
보내지만, `AuthType: NONE`인 Function URL은 그 서명을 검사하지 않는다(무해하게
무시) — 대신 요청을 통과시키려면 "누구든 호출 가능"이라는 별도의 명시적 권한이
있어야 한다. 공개 권한을 제거해버리면 그 "누구든"에 CloudFront도 포함되지 않게
되어 **CloudFront 경유 호출까지 함께 막힌다.**

현재(2026-09-29) 리소스 정책에는 다음 두 statement가 **아직 남아있다**
(`aws lambda get-policy --function-name link-sphere-api --qualifier prod`로 확인
가능) — 지금 당장은 정리 전이라 아래 재추가 명령이 필요 없지만, 나중에 이 둘을
정리한 뒤에는 필요해질 수 있으므로 실제 이름으로 기록해둔다:

- `FunctionURLAllowPublicAccess2` — `lambda:InvokeFunctionUrl`, `principal: "*"`,
  조건 `FunctionUrlAuthType: NONE`(현재 AuthType이 AWS_IAM이라 조건 불일치로 무력)
- `FunctionURLAllowInvokeAction` — `lambda:InvokeFunction`, `principal: "*"`,
  조건 `InvokedViaFunctionUrl: true`

이 두 statement가 제거된 뒤 다시 `AuthType: NONE`으로 되돌리려면 아래로
재추가한다:

```bash
aws lambda add-permission --function-name link-sphere-api --qualifier prod \
  --statement-id FunctionURLAllowPublicAccess2 --action lambda:InvokeFunctionUrl \
  --principal "*" --function-url-auth-type NONE

aws lambda add-permission --function-name link-sphere-api --qualifier prod \
  --statement-id FunctionURLAllowInvokeAction --action lambda:InvokeFunction \
  --principal "*" \
  --function-url-auth-type NONE
```

**즉시 검증**:

```bash
curl -o /dev/null -w '%{http_code}\n' \
  https://452wlgf5pesg75zbpiaotptq7i0ckrsb.lambda-url.ap-northeast-1.on.aws/actuator/health
# 200 기대 (NONE으로 롤백했으므로 직접 호출도 다시 열림)

curl -o /dev/null -w '%{http_code}\n' \
  https://linksphere.click/api/post?page=0\&size=1
# 200 기대
```

## 주의

- **alias를 옮기기 전에 대상 버전을 직접 호출해 연속으로 검증할 것.** 2026-07-25 장애는
  "복원 후 첫 요청은 성공, 이후 실패" 패턴이라 단발 확인으로는 잡히지 않았다.
  ```bash
  aws lambda invoke --function-name link-sphere-api:<버전> --log-type Tail \
    --payload fileb://event.json /tmp/out.json --query 'LogResult' --output text | base64 -d
  ```
- 각 버전은 **발행 시점의 메모리로 구워진 스냅샷**을 갖는다. v39·v43·v44는 1024MB,
  v46은 2048MB다. 따라서 alias를 v43으로 되돌리면 메모리도 1024로 함께 돌아간다.
- `$LATEST`의 설정 변경은 **`publish-version`을 해야** 새 스냅샷에 반영된다.
  `publish-version`은 코드·설정이 이전과 같으면 새 버전을 만들지 않고 기존 버전을 반환한다.
- S3 버전 관리가 비활성이므로 단순 `Expiration`만으로 실제 삭제가 일어난다
  (`NoncurrentVersionExpiration` 불필요).
- IAM 인라인 정책 이름은 `ops-warmup-and-diagnostics`다(초기에는 `warmup-rule-setup`이었으나
  진단 권한을 추가하며 교체). 워밍 핑 삭제·재생성에 이 정책이 필요하다.
