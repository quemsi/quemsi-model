package com.quemsi.model.flow.db.mongodb;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import com.quemsi.commons.util.BaseRuntimeException;

class MongoSubsetSupportTest {

    @Test
    void encodeDecodeObjectIdAsHex() {
        ObjectId oid = new ObjectId();
        String key = MongoSubsetSupport.encodeIdKey(oid);
        assertThat(key, equalTo(oid.toHexString()));
        assertThat(MongoSubsetSupport.decodeIdKey(key), equalTo(oid));
    }

    @Test
    void encodeDecodeStringAndInt() {
        assertThat(MongoSubsetSupport.encodeIdKey("cust-1"), equalTo("cust-1"));
        assertThat(MongoSubsetSupport.decodeIdKey("cust-1"), equalTo("cust-1"));
        assertThat(MongoSubsetSupport.encodeIdKey(42), equalTo("42"));
        assertThat(MongoSubsetSupport.decodeIdKey("42"), equalTo(42));
    }

    @Test
    void encodeDecodeDateAsWrappedExtendedJson() {
        Date date = new Date(1_700_000_000_000L);
        String key = MongoSubsetSupport.encodeIdKey(date);
        Object decoded = MongoSubsetSupport.decodeIdKey(key);
        assertThat(decoded, instanceOf(Date.class));
        assertThat(((Date) decoded).getTime(), equalTo(date.getTime()));
    }

    @Test
    void parseFilterAcceptsJsonAndRejectsSql() {
        Document filter = MongoSubsetSupport.parseFilter("{\"status\":\"ACTIVE\",\"aircraft.size\":{\"$gt\":100}}");
        assertThat(filter.getString("status"), equalTo("ACTIVE"));
        assertThat(((Document) filter.get("aircraft.size")).getInteger("$gt"), equalTo(100));

        assertDoesNotThrow(() -> MongoSubsetSupport.validateFilter("{\"a\":1}"));
        assertThrows(BaseRuntimeException.class, () -> MongoSubsetSupport.parseFilter("t.status = 'A'"));
        assertThrows(BaseRuntimeException.class, () -> MongoSubsetSupport.parseFilter("not-json"));
    }

    @Test
    void emptyFilterIsAllDocuments() {
        assertThat(MongoSubsetSupport.parseFilter(null).isEmpty(), equalTo(true));
        assertThat(MongoSubsetSupport.parseFilter("").isEmpty(), equalTo(true));
    }

    @Test
    void buildPkInFilterJsonUsesExtendedJsonForObjectIds() {
        ObjectId oid = new ObjectId();
        String json = MongoSubsetSupport.buildPkInFilterJson(List.of(oid.toHexString(), "str-id"));
        assertThat(json, containsString("$oid"));
        assertThat(json, containsString(oid.toHexString()));
        Document filter = Document.parse(json);
        Document in = (Document) filter.get("_id");
        @SuppressWarnings("unchecked")
        List<Object> values = (List<Object>) in.get("$in");
        assertThat(values.size(), equalTo(2));
        assertThat(values.get(0), equalTo(oid));
        assertThat(values.get(1), equalTo("str-id"));
    }
}
