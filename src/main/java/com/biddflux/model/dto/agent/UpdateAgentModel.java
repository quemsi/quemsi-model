package com.biddflux.model.dto.agent;

import java.util.ArrayList;

import com.biddflux.model.dto.AgentModel;
import com.biddflux.model.dto.AgentModel.Datasource;
import com.biddflux.model.dto.AgentModel.GoogleDrive;
import com.biddflux.model.dto.AgentModel.LocalDrive;
import com.biddflux.model.dto.AgentModel.Storage;
import com.biddflux.model.dto.AgentModel.Timer;
import com.biddflux.model.dto.FlowDetail;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class UpdateAgentModel extends AgentCommand{
    private AgentModel updatedModel;
    
    public UpdateAgentModel(Long agentId, AgentModel updatedModel){
        super(UpdateAgentModel.class.getSimpleName(), agentId);
        this.updatedModel = updatedModel;
    }

    public static Builder builder(){
        return new Builder();
    }
    public static class Builder{
        private AgentModel model = new AgentModel();
        public Builder agent(Long agentId){
            model.setAgentId(agentId);
            return this;
        }
        public Builder add(FlowDetail flow){
            if(model.getFlows() == null){
                model.setFlows(new ArrayList<>());
            }
            model.getFlows().add(flow);
            return this;
        }
        public Builder add(Datasource ds){
            if(model.getDatasources() == null){
                model.setDatasources(new ArrayList<>());
            }
            model.getDatasources().add(ds);
            return this;
        }
        public Builder add(GoogleDrive gd){
            if(model.getGoogleDrives() == null){
                model.setGoogleDrives(new ArrayList<>());
            }
            model.getGoogleDrives().add(gd);
            return this;
        }
        public Builder add(LocalDrive ld){
            if(model.getLocalDrives() == null){
                model.setLocalDrives(new ArrayList<>());
            }
            model.getLocalDrives().add(ld);
            return this;
        }
        public Builder add(Storage s){
            if(model.getStorages() == null){
                model.setStorages(new ArrayList<>());
            }
            model.getStorages().add(s);
            return this;
        }
        public Builder add(Timer t){
            if(model.getTimers() == null){
                model.setTimers(new ArrayList<>());
            }
            model.getTimers().add(t);
            return this;
        }
        public UpdateAgentModel build(){
            return new UpdateAgentModel(model.getAgentId(), model);
        }
    }
}
