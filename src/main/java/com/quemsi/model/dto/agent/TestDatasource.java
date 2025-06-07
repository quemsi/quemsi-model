package com.quemsi.model.dto.agent;

import com.quemsi.model.dto.AgentModel.Datasource;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class TestDatasource extends AgentCommandSync{
    private Datasource datasource;

    public TestDatasource(){
        super(TestDatasource.class.getSimpleName(), null, null, -1L);
    }
    
    @Builder
    public TestDatasource(Long agentId, Long correlationId, long timeoutMilis, Datasource datasource){
        super(TestDatasource.class.getSimpleName(), agentId, correlationId, timeoutMilis);
        this.datasource = datasource;
    }
}
