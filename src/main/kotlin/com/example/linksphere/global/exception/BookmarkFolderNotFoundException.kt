package com.example.linksphere.global.exception

import java.util.UUID

class BookmarkFolderNotFoundException(id: UUID) : RuntimeException("Bookmark folder not found with id: $id")
