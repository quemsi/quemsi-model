package com.quemsi.model.util;

import java.util.List;

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
    public static String dataFileName(String qualifiedName){
        return "data-" + qualifiedName + ".json";
    }

    public static boolean isEmptyOrNull(List<String> list){
        return list == null || list.isEmpty();
    }
}
