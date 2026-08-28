package com.quemsi.model.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.quemsi.model.dto.AgentModel;
import com.quemsi.model.dto.DataGroup;
import com.quemsi.model.dto.DataType;
import com.quemsi.model.dto.FlowDetail;
import com.quemsi.model.dto.agent.AgentReference;
import com.quemsi.model.dto.agent.UpdateAgentModel;

class CredentialLogSanitizerTest {

    @Test
    void copyMaskedRoundTripsFlowDetailWithSteps() {
        DataGroup data = new DataGroup();
        data.setId(1L);
        data.setName("validation-message");
        data.setTitle("Validation Message");
        data.setType(DataType.DB);

        Map<String, Object> toStep = new HashMap<>();
        toStep.put("type", "To");
        toStep.put("storage", new HashMap<String, Object>());

        AgentReference agent = new AgentReference();
        agent.setId(2L);

        FlowDetail flow = FlowDetail.builder()
            .id(10L)
            .active(true)
            .name("backup-validation-messages-from-local")
            .title("Backup")
            .data(data)
            .back(false)
            .steps(List.of(
                Map.of("type", "From", "datasource", "local"),
                Map.of("type", "Zip"),
                toStep
            ))
            .agent(agent)
            .model("{\"name\":\"backup-validation-messages-from-local\",\"steps\":[{\"type\":\"From\"}]}")
            .build();

        AgentModel model = new AgentModel();
        model.setAgentId(2L);
        model.setFlows(List.of(flow));

        UpdateAgentModel masked = CredentialLogSanitizer.copyMasked(new UpdateAgentModel(2L, model));
        assertThat(masked, notNullValue());
        assertThat(masked.getUpdatedModel().getFlows().get(0).getName(),
            equalTo("backup-validation-messages-from-local"));
        assertThat(masked.getUpdatedModel().getFlows().get(0).isActive(), equalTo(true));
    }
}
