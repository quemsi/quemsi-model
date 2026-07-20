package com.quemsi.model.util;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

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

    /** ANSI/PostgreSQL identifier quoting; escape " as "". */
    public static String doubleQuoted(String identifier) {
        if (identifier == null) {
            return null;
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** Quotes schema and name separately: {@code "schema"."name"}. */
    public static String doubleQuotedQualified(String schema, String name) {
        if (StringUtils.isEmptyOrNull(schema)) {
            return doubleQuoted(name);
        }
        return doubleQuoted(schema) + "." + doubleQuoted(name);
    }

    /**
     * Quotes a {@code schema.name} (or bare name) for PostgreSQL SQL.
     * Splits on the first {@code .} so mixed-case Chinook-style names like {@code public.Genre} work.
     */
    public static String doubleQuotedQualified(String qualifiedName) {
        if (qualifiedName == null) {
            return null;
        }
        int dot = qualifiedName.indexOf('.');
        if (dot < 0) {
            return doubleQuoted(qualifiedName);
        }
        return doubleQuoted(qualifiedName.substring(0, dot)) + "." + doubleQuoted(qualifiedName.substring(dot + 1));
    }
    public static String dataFileName(String qualifiedName){
        return "data-" + qualifiedName + ".json";
    }

    public static boolean isEmptyOrNull(List<String> list){
        return list == null || list.isEmpty();
    }

    public static String addInParameter(String sql, int count){
        StringBuilder sb = new StringBuilder("(");
        for(int i = 0; i < count; i++){
            sb.append("?");
            if(i < count - 1){
                sb.append(", ");
            }
        }
        sb.append(")");
        String processed = sql.replace("{inValues}", sb.toString());
        return processed;
    }
    public static int consumeIndexed(Set<String> values, int startIndex, BiConsumer<Integer, String> consumer){
        AtomicInteger i = new AtomicInteger(startIndex);
        values.forEach(value -> consumer.accept(i.getAndIncrement(), value));
        return i.get();
    }
}
