package com.quemsi.model.dto;

import java.io.Serializable;
import java.util.List;

import com.quemsi.commons.persistence.Views;
import com.fasterxml.jackson.annotation.JsonView;

import lombok.Data;

@Data
@JsonView(Views.Agent.AgentModel.class)
public class AgentModel implements Serializable{
    public Long agentId;
    private List<Datasource> datasources;
    private List<Timer> timers;
    private List<LocalDrive> localDrives;
    private List<GoogleDrive> googleDrives;
    private List<Storage> storages;
    private List<FlowDetail> flows;

    @Data
    @JsonView(Views.Agent.AgentModel.class)
    public static class Storage implements Serializable{
        private Long id;
        private String name;
        private String title;
        private StorageType type;
        private int displayOrder;
        private String loc;
        private String rootPath;
        private String retentionPolicy;
        private long countLimit;
        private long sizeLimit;
        private long capacity;
        private long usedSize;
        private String fullPath;
    }
    @Data
    @JsonView(Views.Agent.AgentModel.class)
    public static class GoogleDrive implements Serializable {
        private Long id;
        private String name;
        private String title;
        private String callbackBaseUrl;
        private Integer callbackPort;
        private long usedSize;
    }
    @Data
    @JsonView(Views.Agent.AgentModel.class)
    public static class LocalDrive implements Serializable {
        private Long id;
        private String name;
        private String title;
        private String storageRoot;
        private long capacity;
        private long usedSize;
    }
    @Data
    @JsonView(Views.Agent.AgentModel.class)
    public static class Timer implements Serializable{
        private Long id;
        private String name;
        private String title;
        private String schedule;
    }
    @Data
    @JsonView(Views.Agent.AgentModel.class)
    public static class Datasource implements Serializable{
        private Long id;
        private DatasourceType type;
        private String name;
        private String dbName;
        private String url;
        private String username;
        private String password;
        private boolean useEnvVar;
    }
}
