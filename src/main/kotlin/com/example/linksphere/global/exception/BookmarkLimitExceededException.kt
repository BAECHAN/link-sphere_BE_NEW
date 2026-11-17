package com.example.linksphere.global.exception

class BookmarkLimitExceededException(limit: Int) : RuntimeException("Bookmark limit exceeded: $limit")
