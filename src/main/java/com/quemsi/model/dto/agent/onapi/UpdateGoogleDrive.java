package com.quemsi.model.dto.agent.onapi;

import com.quemsi.model.dto.agent.AgentCommand;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class UpdateGoogleDrive extends AgentCommand {
    private String driveName;
    private String authUrl;
    private boolean connected;

    @Builder
    public UpdateGoogleDrive(Long agentId, String driveName, boolean connected, String authUrl){
        super(UpdateGoogleDrive.class.getSimpleName(), agentId);
        this.driveName = driveName;
        this.connected = connected;
        this.authUrl = authUrl;
    }
}
