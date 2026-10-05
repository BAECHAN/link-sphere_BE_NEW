package com.example.linksphere.global.exception

/**
 * 업로드 서명 URL을 요청한 확장자가 허용 목록 밖일 때(UploadService). 예전에는 IllegalArgumentException의
 * 공통 매핑 때문에 404 NOT_FOUND로 나가 FE가 "서버 오류"로만 보여줬다 - 사용자가 고칠 수 있는 형식
 * 문제라는 걸 알 수 있게 전용 code로 낸다(docs/plans/2026-10-05-image-upload-lifecycle.md).
 */
class UnsupportedImageTypeException(extension: String) : RuntimeException("Unsupported image extension: $extension")
