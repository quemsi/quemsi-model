package com.biddflux.model.dto;

import java.util.List;

import com.biddflux.commons.persistence.Views;
import com.fasterxml.jackson.annotation.JsonView;

import lombok.Data;

@Data
@JsonView(Views.Agent.AgentModel.class)
public class AgentModel {
    public Long agentId;
    private List<Datasource> datasources;
    private List<Timer> timers;
    private List<LocalDrive> localDrives;
    private List<GoogleDrive> googleDrives;
    private List<Storage> storages;
    private List<FlowDetail> flows;

    @Data
    @JsonView(Views.Agent.AgentModel.class)
    public static class Storage {
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
    }
    @Data
    @JsonView(Views.Agent.AgentModel.class)
    public static class GoogleDrive{
        private Long id;
        private String name;
        private String title;
        private String callbackBaseUrl;
        private Integer callbackPort;
    }
    @Data
    @JsonView(Views.Agent.AgentModel.class)
    public static class LocalDrive{
        private Long id;
        private String name;
        private String title;
        private String storageRoot;
        private long capacity;
    }
    @Data
    @JsonView(Views.Agent.AgentModel.class)
    public static class Timer{
        private Long id;
        private String name;
        private String title;
        private String schedule;
    }
    @Data
    @JsonView(Views.Agent.AgentModel.class)
    public static class Datasource{
        private Long id;
        private DatasourceType type;
        private String name;
        private String dbName;
        private String url;
        private String username;
        private String password;
    }
}
