package com.biddflux.model.dto.agent;

import java.io.Serializable;

import com.biddflux.commons.persistence.Views;
import com.biddflux.model.dto.AgentStatus;
import com.fasterxml.jackson.annotation.JsonView;

import lombok.Data;

@Data
public class AgentReference implements Serializable{
    @JsonView(Views.OnlyIdName.class)
    private Long id;
    @JsonView(Views.OnlyIdName.class)
    private AgentStatus status;
}
