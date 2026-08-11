package com.example.linksphere.global.exception

class DuplicateNicknameException(nickname: String) : RuntimeException("Nickname already exists: $nickname")
