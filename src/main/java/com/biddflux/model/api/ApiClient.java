package com.biddflux.model.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import com.biddflux.model.dto.AgentModel;
import com.biddflux.model.dto.DataVersion;
import com.biddflux.model.dto.FlowExecution;
import com.biddflux.model.dto.FlowExecution.FlowExecutionStep;
import com.biddflux.model.dto.agent.AgentCommand;

public interface ApiClient {
    @GetMapping("/api/agent/all-model")
    AgentModel allModel(String agentVersion);
    @GetMapping("/api/agent/next-command")
    AgentCommand nextCommand();
    @PostMapping("/api/agent/flow-execution")
    FlowExecution saveFlowExecution(FlowExecution execution);
    @PostMapping("/api/agent/flow-execution-step")
    FlowExecutionStep saveFlowExecutionStep(FlowExecutionStep executionStep);
    @GetMapping("/api/agent/gdrive-credentials")
    String googleCredential();
    @PostMapping("/api/agent/agent-command")
    void send(AgentCommand command);
    @GetMapping("/api/agent/find-version/{flowName}")
    DataVersion findVersion(String flowName, Map<String, String> tags);
}

