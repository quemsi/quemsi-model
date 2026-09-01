package com.quemsi.model.dto.agent;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.quemsi.model.dto.agent.onapi.NotifyError;
import com.quemsi.model.dto.agent.onapi.NotifyFlowReady;
import com.quemsi.model.dto.agent.onapi.PreviewSubsetResult;
import com.quemsi.model.dto.agent.onapi.RetentionCompleted;
import com.quemsi.model.dto.agent.onapi.TestAWSS3DriveResult;
import com.quemsi.model.dto.agent.onapi.TestAzureBlobDriveResult;
import com.quemsi.model.dto.agent.onapi.TestDatasourceResult;
import com.quemsi.model.dto.agent.onapi.TestFolderAccessResult;
import com.quemsi.model.dto.agent.onapi.TestRedisResult;
import com.quemsi.model.dto.agent.onapi.VersionDeleted;

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
    @JsonSubTypes.Type(value = UpdateAgentModel.class, name = "UpdateAgentModel"),
    @JsonSubTypes.Type(value = RetentionExecute.class, name = "RetentionExecute"),
    @JsonSubTypes.Type(value = VersionDeleteRequest.class, name = "VersionDeleteRequest"),
    @JsonSubTypes.Type(value = TestDatasource.class, name = "TestDatasource"),
    @JsonSubTypes.Type(value = PreviewSubset.class, name = "PreviewSubset"),
    @JsonSubTypes.Type(value = TestAzureBlobDrive.class, name = "TestAzureBlobDrive"),
    @JsonSubTypes.Type(value = TestAWSS3Drive.class, name = "TestAWSS3Drive"),
    @JsonSubTypes.Type(value = TestFolderAccess.class, name = "TestFolderAccess"),
    @JsonSubTypes.Type(value = TestRedis.class, name = "TestRedis"),

    @JsonSubTypes.Type(value = NotifyError.class, name = "NotifyError"),
    @JsonSubTypes.Type(value = NotifyFlowReady.class, name = "NotifyFlowReady"),
    @JsonSubTypes.Type(value = RetentionCompleted.class, name = "RetentionCompleted"),
    @JsonSubTypes.Type(value = VersionDeleted.class, name = "VersionDeleted"),
    @JsonSubTypes.Type(value = TestDatasourceResult.class, name = "TestDatasourceResult"),
    @JsonSubTypes.Type(value = PreviewSubsetResult.class, name = "PreviewSubsetResult"),
    @JsonSubTypes.Type(value = TestAzureBlobDriveResult.class, name = "TestAzureBlobDriveResult"),
    @JsonSubTypes.Type(value = TestAWSS3DriveResult.class, name = "TestAWSS3DriveResult"),
    @JsonSubTypes.Type(value = TestFolderAccessResult.class, name = "TestFolderAccessResult"),
    @JsonSubTypes.Type(value = TestRedisResult.class, name = "TestRedisResult")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentCommand implements Serializable {
    private String name;
    private Long agentId;
}
