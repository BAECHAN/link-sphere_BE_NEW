package com.example.linksphere.global.exception

class DuplicateFolderNameException(name: String) : RuntimeException("Folder name already exists: $name")
