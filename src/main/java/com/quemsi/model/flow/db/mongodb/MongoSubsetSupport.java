package com.quemsi.model.flow.db.mongodb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.bson.types.ObjectId;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;

/**
 * Mongo subset helpers: native JSON filters and canonical {@code _id} key encoding.
 */
public final class MongoSubsetSupport {
    private static final String COMPLEX_KEY_WRAPPER = "v";

    private MongoSubsetSupport() {
    }

    /**
     * Empty filter = all documents. Non-empty must be a MongoDB JSON query document.
     */
    public static Document parseFilter(String filterJson) {
        if (StringUtils.isEmptyOrNull(filterJson)) {
            return new Document();
        }
        String trimmed = filterJson.trim();
        if (!trimmed.startsWith("{")) {
            throw Exceptions.badRequest("mongo-browse-filter-must-be-json")
                    .withExtra("hint", "Use a MongoDB filter document, e.g. {\"status\":\"ACTIVE\"}")
                    .get();
        }
        try {
            return Document.parse(trimmed);
        } catch (Exception e) {
            throw Exceptions.badRequest("mongo-browse-filter-invalid")
                    .withExtra("hint", "Use a MongoDB filter document, e.g. {\"status\":\"ACTIVE\"}")
                    .withCause(e)
                    .get();
        }
    }

    /** Validates a non-empty Mongo filter document (same rules as {@link #parseFilter}). */
    public static void validateFilter(String filterJson) {
        if (StringUtils.isEmptyOrNull(filterJson)) {
            return;
        }
        parseFilter(filterJson);
    }

    /**
     * Canonical string key for subset plans / browse selection.
     * ObjectId → hex; primitives → plain string; other BSON → wrapped Extended JSON.
     */
    public static String encodeIdKey(Object id) {
        if (id == null) {
            return "";
        }
        if (id instanceof ObjectId oid) {
            return oid.toHexString();
        }
        if (id instanceof String s) {
            return s;
        }
        if (id instanceof Number || id instanceof Boolean) {
            return String.valueOf(id);
        }
        Object json = MongoTypeMapper.toJsonValue(id);
        if (json instanceof Map || json instanceof List) {
            return new Document(COMPLEX_KEY_WRAPPER, json).toJson();
        }
        return String.valueOf(json);
    }

    /** Decode a key from {@link #encodeIdKey} back to a BSON {@code _id} value. */
    public static Object decodeIdKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.startsWith("{")) {
            try {
                Document doc = Document.parse(trimmed);
                if (doc.containsKey(COMPLEX_KEY_WRAPPER)) {
                    return MongoTypeMapper.fromJsonValue(doc.get(COMPLEX_KEY_WRAPPER));
                }
                // Extended JSON ObjectId or other typed value written as the whole document
                if (doc.containsKey("$oid") || doc.containsKey("$date") || doc.containsKey("$numberDecimal")
                        || doc.containsKey("$binary")) {
                    return MongoTypeMapper.fromJsonValue(doc);
                }
                return MongoTypeMapper.fromJsonValue(doc);
            } catch (Exception e) {
                throw Exceptions.badRequest("mongo-id-key-invalid")
                        .withExtra("key", key)
                        .withCause(e)
                        .get();
            }
        }
        if (ObjectId.isValid(trimmed)) {
            return new ObjectId(trimmed);
        }
        if ("true".equals(trimmed) || "false".equals(trimmed)) {
            return Boolean.parseBoolean(trimmed);
        }
        if (looksLikeInteger(trimmed)) {
            try {
                long n = Long.parseLong(trimmed);
                if (n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) {
                    return (int) n;
                }
                return n;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        if (looksLikeDouble(trimmed)) {
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return trimmed;
    }

    /**
     * Builds a Mongo filter JSON string selecting documents by encoded {@code _id} keys.
     * ObjectIds are emitted as {@code {"$oid":"..."}} so {@link Document#parse} works.
     */
    public static String buildPkInFilterJson(Collection<String> encodedKeys) {
        if (encodedKeys == null || encodedKeys.isEmpty()) {
            throw Exceptions.badRequest("subset-pk-selection-required").get();
        }
        List<Object> values = new ArrayList<>();
        for (String key : encodedKeys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Object decoded = decodeIdKey(key);
            if (decoded == null) {
                continue;
            }
            values.add(MongoTypeMapper.toJsonValue(decoded));
        }
        if (values.isEmpty()) {
            throw Exceptions.badRequest("subset-pk-selection-required").get();
        }
        Document filter = new Document("_id", new Document("$in", values));
        return filter.toJson();
    }

    private static boolean looksLikeInteger(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int i = 0;
        if (s.charAt(0) == '-' || s.charAt(0) == '+') {
            i = 1;
        }
        if (i >= s.length()) {
            return false;
        }
        for (; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeDouble(String s) {
        if (s == null || s.isEmpty() || s.indexOf('.') < 0) {
            return false;
        }
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
