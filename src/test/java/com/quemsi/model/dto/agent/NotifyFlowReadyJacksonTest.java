package com.quemsi.model.dto.agent;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.model.dto.agent.onapi.NotifyFlowReady;

class NotifyFlowReadyJacksonTest {
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void roundTripAsAgentCommand() throws Exception {
        NotifyFlowReady original = NotifyFlowReady.builder()
            .agentId(9L)
            .flowName("nightly-backup")
            .build();

        AgentCommand back = om.readValue(om.writeValueAsString(original), AgentCommand.class);

        assertThat(back, instanceOf(NotifyFlowReady.class));
        NotifyFlowReady result = (NotifyFlowReady) back;
        assertThat(result.getFlowName(), equalTo("nightly-backup"));
        assertThat(result.getAgentId(), equalTo(9L));
        assertThat(result.getName(), equalTo("NotifyFlowReady"));
    }
}
