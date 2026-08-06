# Lambda 배포 설정 롤백 런북

2026-07-25 502 장애 대응 과정에서 작성된 변경 전 상태 스냅샷 겸, 이후에도
prod alias를 안전 버전으로 되돌릴 때 계속 참고하는 롤백 절차서다.

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

## 최종 상태 (작업 완료 시점)

| 항목 | 변경 전 | **현재** |
| ---- | ------- | -------- |
| `prod` alias | 39 | **46** |
| LWA 레이어 | `LambdaAdapterLayerArm64:24` 부착 | **제거됨** |
| 워밍업 코드 | 없음 | **적용됨** (경로 `/common/category-option`, 2xx 검증) |
| 메모리 | 1024 | **2048** |
| EventBridge 워밍 핑 | 없음 | **적용됨** (`rate(5 minutes)`, `prod` 대상) |
| S3 수명 주기 | 없음 | **적용됨** (`deployments/` 30일 만료) |
| AWS CLI 기본 리전 | `northeast-1` (오타) | `ap-northeast-1` |

> 중간 경과: v40(워밍업+2048) 배포 → 502 장애 → v39 롤백 → v42(워밍업 철회) →
> v43(LWA 제거) → v44/v45(워밍업 재투입) → **v46(메모리 2048)**.
> 자세한 경위는 [PERFORMANCE.md](./PERFORMANCE.md) 5장.

### 알려진 안전 지점

| 버전 | 구성 | 비고 |
| ---- | ---- | ---- |
| **v46** | LWA 없음 + 워밍업 + 2048MB | **현재 prod.** 콜드 첫 요청이 가장 빠름 |
| v43 | LWA 없음 + 워밍업 없음 | 워밍업을 의심할 때 되돌릴 지점 |
| v39 | LWA 있음 + 워밍업 없음 | 장애 이전 원본 상태 |

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

# 1. 즉시 롤백 — 위 "알려진 안전 지점" 표에서 대상 버전 선택
aws lambda update-alias --function-name $F --name prod --function-version 43
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
