package com.metrada

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MetradaApplication

fun main(args: Array<String>) {
    runApplication<MetradaApplication>(*args)
}
