package com.quemsi.model.dto.agent;

import com.quemsi.model.dto.AgentModel.Datasource;
import com.quemsi.model.flow.subset.SubsetConfig;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PreviewSubset extends AgentCommandSync {
    private Datasource datasource;
    private SubsetConfig subset;

    public PreviewSubset() {
        super(PreviewSubset.class.getSimpleName(), null, null, -1L);
    }

    @Builder
    public PreviewSubset(Long agentId, Long correlationId, long timeoutMilis,
            Datasource datasource, SubsetConfig subset) {
        super(PreviewSubset.class.getSimpleName(), agentId, correlationId, timeoutMilis);
        this.datasource = datasource;
        this.subset = subset;
    }
}
