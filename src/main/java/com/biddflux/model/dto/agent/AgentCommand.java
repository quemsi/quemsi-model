package com.biddflux.model.dto.agent;

import java.io.Serializable;

import com.biddflux.model.dto.agent.onapi.NotifyError;
import com.biddflux.model.dto.agent.onapi.RetentionCompleted;
import com.biddflux.model.dto.agent.onapi.UpdateGoogleDrive;
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
    @JsonSubTypes.Type(value = DelayAgentCommand.class, name = "DelayAgentCommand"),
    @JsonSubTypes.Type(value = GoogleDriveConnect.class, name = "GoogleDriveConnect"),
    @JsonSubTypes.Type(value = UpdateAgentModel.class, name = "UpdateAgentModel"),
    @JsonSubTypes.Type(value = RetentionExecute.class, name = "RetentionExecute"),

    @JsonSubTypes.Type(value = NotifyError.class, name = "NotifyError"),
    @JsonSubTypes.Type(value = UpdateGoogleDrive.class, name = "UpdateGoogleDrive"),
    @JsonSubTypes.Type(value = RetentionCompleted.class, name = "RetentionCompleted")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentCommand implements Serializable {
    private String name;
    private Long agentId;
}
