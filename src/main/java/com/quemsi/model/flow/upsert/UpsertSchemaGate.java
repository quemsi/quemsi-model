package com.quemsi.model.flow.upsert;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.util.CommonHelpers;

/**
 * Read-only compatibility check for selected tables. Does not apply DDL.
 */
public class UpsertSchemaGate {

    public void assertCompatible(DbModel sourceModel, DbModel targetModel, List<String> tableNames) {
        List<String> problems = new ArrayList<>();
        for (String tableName : tableNames) {
            DbTable source = UpsertTables.resolve(sourceModel, tableName);
            DbTable target = UpsertTables.find(targetModel, tableName).orElse(null);
            if (target == null) {
                problems.add(tableName + ": missing on target");
                continue;
            }
            if (!CommonHelpers.isEmptyOrNull(source.getPkColumnNames())) {
                List<String> sourcePk = source.getPkColumnNames();
                List<String> targetPk = target.getPkColumnNames() != null ? target.getPkColumnNames() : List.of();
                if (!sourcePk.equals(targetPk)) {
                    problems.add(tableName + ": primary key mismatch source=" + sourcePk + " target=" + targetPk);
                }
            }
            for (DbColumn sourceColumn : source.orderedColumns()) {
                DbColumn targetColumn = target.column(sourceColumn.getName());
                if (targetColumn == null) {
                    problems.add(tableName + "." + sourceColumn.getName() + ": missing on target");
                    continue;
                }
                if (!compatibleType(sourceColumn, targetColumn)) {
                    problems.add(tableName + "." + sourceColumn.getName()
                        + ": incompatible type source=" + typeLabel(sourceColumn)
                        + " target=" + typeLabel(targetColumn));
                }
            }
        }
        if (!problems.isEmpty()) {
            throw Exceptions.badRequest("upsert-schema-incompatible")
                .withExtra("problems", problems)
                .get();
        }
    }

    static boolean compatibleType(DbColumn source, DbColumn target) {
        String left = baseType(source);
        String right = baseType(target);
        if (left == null || right == null) {
            return true;
        }
        if (left.equals(right)) {
            return true;
        }
        return typeFamily(left).equals(typeFamily(right));
    }

    static String baseType(DbColumn column) {
        if (column == null) {
            return null;
        }
        String type = column.getDataType();
        if (type == null || type.isBlank()) {
            type = column.getColumnType();
        }
        if (type == null || type.isBlank()) {
            return null;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        int paren = normalized.indexOf('(');
        if (paren > 0) {
            normalized = normalized.substring(0, paren).trim();
        }
        int dot = normalized.lastIndexOf('.');
        if (dot >= 0 && dot < normalized.length() - 1) {
            normalized = normalized.substring(dot + 1);
        }
        return normalized;
    }

    private static String typeFamily(String baseType) {
        return switch (baseType) {
            case "INT", "INTEGER", "INT4", "INT2", "SMALLINT", "TINYINT", "MEDIUMINT",
                    "BIGINT", "INT8", "SERIAL", "BIGSERIAL", "SMALLSERIAL" -> "INT";
            case "NUMERIC", "DECIMAL", "NUMBER", "MONEY", "SMALLMONEY" -> "DECIMAL";
            case "FLOAT", "FLOAT4", "FLOAT8", "REAL", "DOUBLE", "DOUBLE PRECISION" -> "FLOAT";
            case "CHAR", "NCHAR", "VARCHAR", "NVARCHAR", "CHARACTER", "CHARACTER VARYING",
                    "TEXT", "NTEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB", "NCLOB" -> "CHAR";
            case "BOOL", "BOOLEAN", "BIT" -> "BOOL";
            case "DATE" -> "DATE";
            case "TIME" -> "TIME";
            case "TIMESTAMP", "TIMESTAMPTZ", "DATETIME", "DATETIME2", "SMALLDATETIME",
                    "DATETIMEOFFSET", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITHOUT TIME ZONE" -> "TIMESTAMP";
            default -> baseType;
        };
    }

    private static String typeLabel(DbColumn column) {
        if (column.getColumnType() != null && !column.getColumnType().isBlank()) {
            return column.getColumnType();
        }
        return Objects.toString(column.getDataType(), "");
    }
}
