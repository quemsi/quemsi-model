package com.quemsi.model.dto.agent;

import java.io.Serializable;

import com.quemsi.commons.persistence.Views;
import com.quemsi.model.dto.AgentStatus;
import com.fasterxml.jackson.annotation.JsonView;

import lombok.Data;

@Data
public class AgentReference implements Serializable{
    @JsonView(Views.OnlyIdName.class)
    private Long id;
    @JsonView(Views.OnlyIdName.class)
    private String name;
    @JsonView(Views.OnlyIdName.class)
    private AgentStatus status;
}
