package com.quemsi.model.dto;

import java.io.Serializable;

import com.quemsi.commons.persistence.Views;
import com.fasterxml.jackson.annotation.JsonView;

import lombok.Data;

@Data
public class ObjectReference implements Serializable{
    @JsonView(Views.OnlyIdName.class)
    private Long id;
}
