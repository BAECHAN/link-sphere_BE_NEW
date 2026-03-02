package com.example.linksphere.global.exception

import java.util.UUID

class PostNotFoundException(id: UUID) : RuntimeException("Post not found with id: $id")
