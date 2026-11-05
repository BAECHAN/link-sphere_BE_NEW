package com.example.linksphere.global.exception

class DuplicatePostException(url: String) : RuntimeException("Same URL was posted recently: $url")
