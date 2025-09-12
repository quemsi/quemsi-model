package com.quemsi.model.dto.agent.onapi;

import java.util.List;

import com.quemsi.model.dto.DataVersion;
import com.quemsi.model.dto.agent.AgentCommand;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RetentionCompleted extends AgentCommand{
    private Long storageId;
    private String storageName;
    private List<Long> files;
    private List<DataVersion> versions;

    @Builder
    public RetentionCompleted(Long agentId, Long storageId, String storageName, List<Long> files, List<DataVersion> versions){
        super(RetentionCompleted.class.getSimpleName(), agentId);
        this.storageId = storageId;
        this.storageName = storageName;
        this.files = files;
        this.versions = versions;
    }
}
