package com.quemsi.model.dto.agent;

import java.util.List;
import java.util.Map;

import com.quemsi.model.dto.DataFile;
import com.quemsi.model.dto.FlowDetail;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class ExecuteEphemeralFlow extends AgentCommand {
    private String flowName;
    private Long versionId;
    private Map<String, String> tags;
    private List<DataFile> files;
    private Long flowExecutionId;
    private FlowDetail flow;

    @Builder
    public ExecuteEphemeralFlow(Long agentId, String flowName, Long versionId, Map<String, String> tags,
            List<DataFile> files, Long flowExecutionId, FlowDetail flow) {
        super(ExecuteEphemeralFlow.class.getSimpleName(), agentId);
        this.flowName = flowName;
        this.versionId = versionId;
        this.tags = tags;
        this.files = files;
        this.flowExecutionId = flowExecutionId;
        this.flow = flow;
    }
}
