package com.example.linksphere

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling @SpringBootApplication class LinkSphereBeApplication

fun main(args: Array<String>) {
    runApplication<LinkSphereBeApplication>(*args)
}
