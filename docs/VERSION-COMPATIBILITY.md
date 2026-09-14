# Link-Sphere — BE·FE 버전 호환 매트릭스

> **정본은 FE 레포에 있습니다** — 아래 안내 참고

BE·FE는 레포가 분리돼 있고 SemVer도 각자 독립적으로 올라가지만, 하나의 앱을 1인이
같이 개발·배포한다. API 계약(요청/응답 스펙, 필드 추가·제거, 공개 범위 변경 등)이
걸린 릴리즈는 상대 레포의 특정 버전 이상을 요구하는데, 그 사실을 한 곳에 모아 "지금
이 조합으로 배포해도 되는지"를 바로 확인할 수 있게 하는 문서다.

> **📌 2026-09-14부터 이 문서는 BE 자체 사본을 두지 않습니다.**
> 도입 시점부터 BE·FE 양쪽에 동일한 표를 유지하기로 했었지만, 2026-08-13 이후 BE
> 사본만 갱신이 끊겨 FE 정본과 3개 버전만큼 벌어진 채 방치돼 있었습니다(과거 릴리즈
> 커밋을 확인한 결과 BE 사본이 실제로 함께 갱신된 적이 한 번도 없었습니다). BE
> `docs/HISTORY.md`를 2026-08-01에 같은 방식으로 정리한 선례를 따라, 정본을 FE
> 레포로 통합했습니다. 호환 매트릭스는 FE 레포
> [`docs/VERSION-COMPATIBILITY.md`](https://github.com/BAECHAN/link-sphere_FE_NEW/blob/main/docs/VERSION-COMPATIBILITY.md)에서
> 확인하세요. 결정 경위는 FE 레포
> [`docs/DECISIONS.md`](https://github.com/BAECHAN/link-sphere_FE_NEW/blob/main/docs/DECISIONS.md)
> 2026-09-14 "BE·FE 버전 호환 매트릭스 중복 제거" 항목을 참고하세요. BE 자체의 상세
> 변경 이력은 [`CHANGELOG.md`](../CHANGELOG.md)를 참고하세요.
