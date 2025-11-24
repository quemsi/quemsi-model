package com.quemsi.model.util;

import com.quemsi.commons.util.StringUtils;

public class CommonHelpers {
    public static String qualifiedName(String schema, String name){
        StringBuilder sb = new StringBuilder();
        if(!StringUtils.isEmptyOrNull(schema)){
            sb.append(schema).append(".");
        }
        sb.append(name);
        return sb.toString();
    }
}
