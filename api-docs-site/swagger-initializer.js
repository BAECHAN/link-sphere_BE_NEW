// 공개 API 문서(https://baechan.github.io/link-sphere_BE_NEW/)의 Swagger UI 설정. swagger-ui webjar의 기본 파일(petstore 예제
// 스펙을 부른다)을 대신해 build.gradle.kts의 swaggerUiSite 태스크가 덮어 넣는다.
// - 읽기 전용: supportedSubmitMethods를 비워 "Try it out"을 끈다 - 이 페이지에서 운영 API로 요청이 나가지 않는다.
// - 외부 요청 없음: validatorUrl을 끄지 않으면 스펙을 validator.swagger.io로 보내 검증 배지를 그린다.
// - 상단 URL 입력창 없는 BaseLayout, 쿼리 문자열로 설정을 바꾸지 못하게, 인가 정보는 저장하지 않는다(github.io 출처를 다른
//   Pages 저장소와 같이 쓴다).
// 근거: https://swagger.io/docs/open-source-tools/swagger-ui/usage/configuration/, docs/plans/2026-10-30-docs-to-github-pages.md
window.onload = function () {
  window.ui = SwaggerUIBundle({
    url: './openapi.json',
    dom_id: '#swagger-ui',
    deepLinking: true,
    presets: [SwaggerUIBundle.presets.apis],
    layout: 'BaseLayout',
    supportedSubmitMethods: [],
    validatorUrl: null,
    queryConfigEnabled: false,
    persistAuthorization: false,
  });
};
