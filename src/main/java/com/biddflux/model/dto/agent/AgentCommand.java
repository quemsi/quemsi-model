package com.biddflux.model.dto.agent;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@JsonTypeInfo(
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "name",
    use = JsonTypeInfo.Id.NAME,
    visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ExecuteFlow.class, name = "ExecuteFlow"),
        @JsonSubTypes.Type(value = DelayAgentCommand.class, name = "DelayAgentCommand")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentCommand {
    private String name;
    private Long agentId;
}
