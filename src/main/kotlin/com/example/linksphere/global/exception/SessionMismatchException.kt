package com.example.linksphere.global.exception

/**
 * refresh에 실린 access 토큰의 회원과 refresh 쿠키의 회원이 다를 때(MemberSessionService.rotate). 같은 브라우저의
 * 다른 탭에서 다른 계정으로 로그인해 공유 쿠키가 바뀐 경우다 - 발급하면 원 요청이 다른 계정으로 재실행된다.
 * 쿠키는 유효하고 두 자격이 서로 맞지 않는 상황이라 401(다시 로그인하라)이 아니라 409로 알린다.
 * RuntimeException이어야 한다 - rotate 트랜잭션을 롤백시켜 앞서 한 refresh 소비를 되돌리는 데 기댄다.
 */
class SessionMismatchException : RuntimeException("Access token does not belong to the refresh token's member")
