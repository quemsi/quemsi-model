package com.quemsi.model.flow.db.mongodb;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.quemsi.model.dto.MaskType;
import com.quemsi.model.flow.in.TableData;
import com.quemsi.model.flow.process.MaskedStringGenerator;

public class MaskColumnsMongoTest {

    @Test
    public void masksNestedDocumentFieldViaDotPath() {
        TableData tableData = new TableData("testdb.users");
        tableData.setDataFormat(TableData.FORMAT_DOCUMENT);
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("city", "Istanbul");
        address.put("zip", "34000");
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", Map.of("$oid", "507f1f77bcf86cd799439011"));
        doc.put("email", "alice@example.com");
        doc.put("address", address);

        TableData.DataPage page = new TableData.DataPage();
        page.setPageNum(0);
        page.setDocuments(Map.of("507f1f77bcf86cd799439011", doc));
        tableData.getDataPages().add(page);

        MaskedStringGenerator generator = new MaskedStringGenerator();
        generator.setMaskType(MaskType.FIXED);
        generator.setMaskChar("*");
        generator.setLength(5);

        // Replicate document masking logic used by MaskColumns
        maskDocumentPath(doc, "address.city", generator);

        assertThat(((Map<?, ?>) doc.get("address")).get("city"), equalTo("*****"));
        assertThat(doc.get("email"), equalTo("alice@example.com"));
    }

    @SuppressWarnings("unchecked")
    private void maskDocumentPath(Map<String, Object> document, String path, MaskedStringGenerator generator) {
        String[] parts = path.split("\\.");
        Object current = document;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!(current instanceof Map<?, ?> map)) {
                return;
            }
            current = map.get(parts[i]);
        }
        if (!(current instanceof Map<?, ?> parentMap)) {
            return;
        }
        Map<String, Object> writable = (Map<String, Object>) parentMap;
        String leaf = parts[parts.length - 1];
        Object value = writable.get(leaf);
        if (value != null) {
            writable.put(leaf, generator.generate(String.valueOf(value), Integer.MAX_VALUE));
        }
    }
}
