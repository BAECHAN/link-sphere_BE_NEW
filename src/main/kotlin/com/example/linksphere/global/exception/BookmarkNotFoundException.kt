package com.example.linksphere.global.exception

import java.util.UUID

class BookmarkNotFoundException(userId: UUID, postId: UUID) : RuntimeException("Bookmark not found for user=$userId, post=$postId")
