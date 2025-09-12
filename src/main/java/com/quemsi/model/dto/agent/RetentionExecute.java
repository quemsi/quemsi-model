package com.quemsi.model.dto.agent;

import java.io.Serializable;
import java.util.List;

import com.quemsi.model.dto.DataVersion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RetentionExecute extends AgentCommand{
    private Long storageId;
    private String storageName;
    private List<FileInfo> files;
    private List<DataVersion> versions;

    @Builder
    public RetentionExecute(Long agentId, Long storageId, String storageName, List<FileInfo> files, List<DataVersion> versions){
        super(RetentionExecute.class.getSimpleName(), agentId);
        this.storageId = storageId;
        this.storageName = storageName;
        this.files = files;
        this.versions = versions;
    }
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileInfo implements Serializable {
        private Long id;
        private String dir;
        private String name;
    }
}

