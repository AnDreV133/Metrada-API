package com.metrada.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Deprecated("AgentController contains this functions")
@RestController
@RequestMapping("/v1")
class ManagementController {
    @GetMapping("/health")
    fun getHealth() {
        // todo
    }

    @GetMapping("/reload")
    fun getReload() {
        // todo
    }

}