package com.quemsi.model.flow.db.mongodb;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

/**
 * Converts BSON values to/from Extended JSON-style maps for backup serialization.
 */
public final class MongoTypeMapper {
    private MongoTypeMapper() {}

    public static Map<String, Object> documentToMap(Document document) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            result.put(entry.getKey(), toJsonValue(entry.getValue()));
        }
        return result;
    }

    public static Document mapToDocument(Map<String, Object> map) {
        Document document = new Document();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            document.put(entry.getKey(), fromJsonValue(entry.getValue()));
        }
        return document;
    }

    public static Object toJsonValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ObjectId oid) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("$oid", oid.toHexString());
            return m;
        }
        if (value instanceof Date date) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("$date", date.toInstant().toString());
            return m;
        }
        if (value instanceof Decimal128 decimal128) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("$numberDecimal", decimal128.toString());
            return m;
        }
        if (value instanceof Binary binary) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("$binary", Base64.getEncoder().encodeToString(binary.getData()));
            m.put("$type", String.format("%02x", binary.getType()));
            return m;
        }
        if (value instanceof byte[] bytes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("$binary", Base64.getEncoder().encodeToString(bytes));
            m.put("$type", "00");
            return m;
        }
        if (value instanceof Document doc) {
            return documentToMap(doc);
        }
        if (value instanceof List<?> list) {
            List<Object> converted = new ArrayList<>(list.size());
            for (Object item : list) {
                converted.add(toJsonValue(item));
            }
            return converted;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                converted.put(String.valueOf(entry.getKey()), toJsonValue(entry.getValue()));
            }
            return converted;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Object fromJsonValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = (Map<String, Object>) map;
            if (typed.containsKey("$oid")) {
                return new ObjectId(String.valueOf(typed.get("$oid")));
            }
            if (typed.containsKey("$date")) {
                Object dateVal = typed.get("$date");
                if (dateVal instanceof Number number) {
                    return new Date(number.longValue());
                }
                return Date.from(java.time.Instant.parse(String.valueOf(dateVal)));
            }
            if (typed.containsKey("$numberDecimal")) {
                return Decimal128.parse(String.valueOf(typed.get("$numberDecimal")));
            }
            if (typed.containsKey("$binary")) {
                byte[] data = Base64.getDecoder().decode(String.valueOf(typed.get("$binary")));
                byte type = 0;
                if (typed.containsKey("$type")) {
                    type = (byte) Integer.parseInt(String.valueOf(typed.get("$type")), 16);
                }
                return new Binary(type, data);
            }
            Document nested = new Document();
            for (Map.Entry<String, Object> entry : typed.entrySet()) {
                nested.put(entry.getKey(), fromJsonValue(entry.getValue()));
            }
            return nested;
        }
        if (value instanceof List<?> list) {
            List<Object> converted = new ArrayList<>(list.size());
            for (Object item : list) {
                converted.add(fromJsonValue(item));
            }
            return converted;
        }
        return value;
    }

    public static String inferBsonTypeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof ObjectId) {
            return "objectId";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Integer) {
            return "int";
        }
        if (value instanceof Long) {
            return "long";
        }
        if (value instanceof Double) {
            return "double";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof Date) {
            return "date";
        }
        if (value instanceof Decimal128) {
            return "decimal";
        }
        if (value instanceof Binary || value instanceof byte[]) {
            return "binData";
        }
        if (value instanceof Document || value instanceof Map) {
            return "object";
        }
        if (value instanceof List) {
            return "array";
        }
        return value.getClass().getSimpleName().toLowerCase();
    }

    public static Object idKey(Object id) {
        Object json = toJsonValue(id);
        if (json instanceof Map || json instanceof List) {
            return String.valueOf(json);
        }
        return json;
    }
}
