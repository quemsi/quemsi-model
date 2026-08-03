package com.quemsi.model.dto.agent;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.model.dto.agent.onapi.PreviewSubsetResult;
import com.quemsi.model.flow.subset.SubsetPlan.SubsetTableSummary;

class PreviewSubsetResultJacksonTest {
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void roundTripAsAgentCommand() throws Exception {
        PreviewSubsetResult original = PreviewSubsetResult.builder()
            .agentId(1L)
            .correlationId(2L)
            .timeoutMilis(60_000L)
            .success(true)
            .message("subset-preview-ok")
            .tables(List.of(SubsetTableSummary.builder()
                .table("public.data")
                .count(3)
                .driverCount(2)
                .requiredByFkCount(1)
                .requiredBy(List.of("public.child"))
                .build()))
            .build();

        String json = om.writeValueAsString(original);
        AgentCommand back = om.readValue(json, AgentCommand.class);

        assertThat(back, instanceOf(PreviewSubsetResult.class));
        PreviewSubsetResult result = (PreviewSubsetResult) back;
        assertThat(result.isSuccess(), equalTo(true));
        assertThat(result.getCorrelationId(), equalTo(2L));
        assertThat(result.getTables().size(), equalTo(1));
        assertThat(result.getTables().get(0).getTable(), equalTo("public.data"));
        assertThat(result.getTables().get(0).getCount(), equalTo(3L));
    }
}
