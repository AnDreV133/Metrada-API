package com.metrada.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
class QueryController {

    @GetMapping("/query")
    fun getQuery() {
        // todo
    }

    @GetMapping("/query_range")
    fun getQueryRange() {
        // todo
    }
}