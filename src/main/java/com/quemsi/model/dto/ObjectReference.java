package com.quemsi.model.dto;

import java.io.Serializable;

import lombok.Data;

@Data
public class ObjectReference implements Serializable{
    private Long id;

    public static ObjectReference with(Long id){
        ObjectReference r = new ObjectReference();
        r.setId(id);
        return r;
    }
}
