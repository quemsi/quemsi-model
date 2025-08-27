package com.quemsi.model.dto.agent;

import java.util.ArrayList;

import com.quemsi.model.dto.AgentModel;
import com.quemsi.model.dto.AgentModel.AzureBlobDrive;
import com.quemsi.model.dto.AgentModel.Datasource;
import com.quemsi.model.dto.AgentModel.LocalDrive;
import com.quemsi.model.dto.AgentModel.Storage;
import com.quemsi.model.dto.AgentModel.Timer;
import com.quemsi.model.dto.FlowDetail;

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
        public Builder add(AzureBlobDrive ld){
            if(model.getAzureBlobDrives() == null){
                model.setAzureBlobDrives(new ArrayList<>());
            }
            model.getAzureBlobDrives().add(ld);
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
