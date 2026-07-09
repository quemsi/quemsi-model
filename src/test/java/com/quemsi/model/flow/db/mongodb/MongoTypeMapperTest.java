package com.quemsi.model.flow.db.mongodb;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

public class MongoTypeMapperTest {

    @Test
    public void roundTripsObjectIdAndNestedDocument() {
        ObjectId id = new ObjectId();
        Document original = new Document("_id", id)
                .append("name", "alice")
                .append("address", new Document("city", "Istanbul"))
                .append("createdAt", new Date(0));

        Map<String, Object> json = MongoTypeMapper.documentToMap(original);
        assertThat(((Map<?, ?>) json.get("_id")).get("$oid"), equalTo(id.toHexString()));

        Document restored = MongoTypeMapper.mapToDocument(json);
        assertThat(restored.get("_id"), equalTo(id));
        assertThat(restored.get("name"), equalTo("alice"));
        assertThat(restored.get("address"), instanceOf(Document.class));
        assertThat(((Document) restored.get("address")).getString("city"), equalTo("Istanbul"));
        assertThat(restored.get("createdAt"), equalTo(new Date(0)));
    }

    @Test
    public void idKeyUsesExtendedJsonForObjectId() {
        ObjectId id = new ObjectId("507f1f77bcf86cd799439011");
        Object key = MongoTypeMapper.idKey(id);
        assertThat(String.valueOf(key).contains("507f1f77bcf86cd799439011"), equalTo(true));
    }

    @Test
    public void fromJsonValueParsesOidMap() {
        Map<String, Object> oid = new LinkedHashMap<>();
        oid.put("$oid", "507f1f77bcf86cd799439011");
        Object value = MongoTypeMapper.fromJsonValue(oid);
        assertThat(value, equalTo(new ObjectId("507f1f77bcf86cd799439011")));
    }
}
