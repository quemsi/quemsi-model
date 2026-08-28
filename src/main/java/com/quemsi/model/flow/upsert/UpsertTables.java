package com.quemsi.model.flow.upsert;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbTable;

final class UpsertTables {
    private UpsertTables() {
    }

    static DbTable resolve(DbModel model, String name) {
        return find(model, name).orElseThrow(() -> Exceptions.badRequest("upsert-table-not-in-model")
            .withExtra("table", name)
            .get());
    }

    static Optional<DbTable> find(DbModel model, String name) {
        if (model == null || model.getTables() == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        Optional<DbTable> exact = model.findTable(name);
        if (exact.isPresent()) {
            return exact;
        }
        List<DbTable> caseInsensitive = new ArrayList<>();
        List<DbTable> bare = new ArrayList<>();
        String needle = name.trim();
        String bareNeedle = bareName(needle).toLowerCase(Locale.ROOT);
        for (DbTable table : model.getTables().values()) {
            if (table.qualifiedName().equalsIgnoreCase(needle)) {
                caseInsensitive.add(table);
            } else if (table.getName() != null && table.getName().equalsIgnoreCase(bareNeedle)) {
                bare.add(table);
            }
        }
        if (caseInsensitive.size() == 1) {
            return Optional.of(caseInsensitive.get(0));
        }
        if (bare.size() == 1) {
            return Optional.of(bare.get(0));
        }
        return Optional.empty();
    }

    private static String bareName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }
}
