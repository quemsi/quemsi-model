package com.biddflux.model.dto.agent;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class GoogleDriveConnect extends AgentCommand {
    private String driveName;
    private boolean connect;

    @Builder
    public GoogleDriveConnect(Long agentId, String driveName, boolean connect){
        super(GoogleDriveConnect.class.getSimpleName(), agentId);
        this.driveName = driveName;
        this.connect = connect;
    }
}
