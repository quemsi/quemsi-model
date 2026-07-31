package com.quemsi.model.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import com.quemsi.model.dto.AgentModel;
import com.quemsi.model.dto.DataVersion;
import com.quemsi.model.dto.FlowExecution;
import com.quemsi.model.dto.FlowExecution.FlowExecutionStep;
import com.quemsi.model.dto.agent.AgentCommand;

public interface ApiClient {
    @GetMapping("/api/agent/all-model")
    AgentModel allModel(String agentVersion, String runtime);
    @GetMapping("/api/agent/next-command")
    AgentCommand nextCommand();
    @PostMapping("/api/agent/flow-execution")
    FlowExecution saveFlowExecution(FlowExecution execution);
    @PostMapping("/api/agent/flow-execution-step")
    FlowExecutionStep saveFlowExecutionStep(FlowExecutionStep executionStep);
    @PostMapping("/api/agent/initate/{flowName}")
    FlowExecution initiate(String flowName, Map<String, String> tags);
    @GetMapping("/api/agent/gdrive-credentials")
    String googleCredential();
    @PostMapping("/api/agent/agent-command")
    void send(AgentCommand command);
    @GetMapping("/api/agent/find-version/{flowName}")
    DataVersion findVersion(String flowName, Map<String, String> tags);
}

