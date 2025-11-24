package com.quemsi.model.dto.agent;

import java.io.Serializable;

import com.quemsi.model.dto.AgentStatus;

import lombok.Data;

@Data
public class AgentReference implements Serializable{
    private Long id;
    private String name;
    private AgentStatus status;
}
