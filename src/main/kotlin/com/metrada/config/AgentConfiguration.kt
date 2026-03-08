package com.metrada.config

import com.metrada.model.AgentConfigModel
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Deprecated("using entity")
@Component
class AgentConfiguration {
    
    private val agents = ConcurrentHashMap<String, AgentConfigModel>()
    
    init {
        // Добавляем тестовых агентов для примера
        agents["agent-1"] = AgentConfigModel(
            id = "agent-1",
            host = "localhost",
            port = 9090,
            path = "/metrics",
            scrapeIntervalSeconds = 15,
            enabled = true
        )
        
        agents["agent-2"] = AgentConfigModel(
            id = "agent-2",
            host = "192.168.1.100",
            port = 9100,  // node_exporter
            path = "/metrics",
            scrapeIntervalSeconds = 30,
            enabled = true
        )
    }
    
    fun getActiveAgents(): List<AgentConfigModel> =
        agents.values.filter { it.enabled }
    
    fun getAllAgents(): List<AgentConfigModel> =
        agents.values.toList()
    
    fun getAgent(id: String): AgentConfigModel? = agents[id]
    
    fun addAgent(agent: AgentConfigModel) {
        agents[agent.id] = agent
    }
    
    fun removeAgent(id: String) {
        agents.remove(id)
    }
    
    fun updateAgent(id: String, agent: AgentConfigModel) {
        if (agents.containsKey(id)) {
            agents[id] = agent
        }
    }
}