package com.quemsi.model.flow.upsert;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.subset.SqlSubsetSupport;
import com.quemsi.model.flow.subset.SqlSubsetSupport.ColumnQuoter;
import com.quemsi.model.flow.subset.SqlSubsetSupport.TableQuoter;
import com.quemsi.model.util.CommonHelpers;

public final class SqlUpsertSupport {
    public static final int IN_BATCH_SIZE = SqlSubsetSupport.IN_BATCH_SIZE;

    @FunctionalInterface
    public interface SqlRunnable {
        void run() throws SQLException;
    }

    private SqlUpsertSupport() {
    }

    public static String encodeKey(Object... parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        if (parts.length == 1) {
            return SqlSubsetSupport.canonicalPkPart(parts[0]);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(DataSourceFactory.PK_VALUES_SEPERATOR);
            }
            sb.append(SqlSubsetSupport.canonicalPkPart(parts[i]));
        }
        return sb.toString();
    }

    public static TableQuoter tableQuoter(DatasourceType type) {
        return switch (type) {
            case MYSQL -> SqlUpsertSupport::quoteMysqlTable;
            case POSTGRES, ORACLE -> t -> CommonHelpers.doubleQuotedQualified(t.getSchema(), t.getName());
            case SQLSERVER -> t -> CommonHelpers.bracketQuotedQualified(t.getSchema(), t.getName());
            default -> throw Exceptions.badRequest("upsert-unsupported-datasource")
                .withExtra("type", type)
                .get();
        };
    }

    public static ColumnQuoter columnQuoter(DatasourceType type) {
        return switch (type) {
            case MYSQL -> name -> "`" + name.replace("`", "``") + "`";
            case POSTGRES, ORACLE -> CommonHelpers::doubleQuoted;
            case SQLSERVER -> CommonHelpers::bracketQuoted;
            default -> throw Exceptions.badRequest("upsert-unsupported-datasource")
                .withExtra("type", type)
                .get();
        };
    }

    private static String quoteMysqlTable(DbTable table) {
        if (!com.quemsi.commons.util.StringUtils.isEmptyOrNull(table.getSchema())) {
            return "`" + table.getSchema() + "`.`" + table.getName() + "`";
        }
        return "`" + table.getName() + "`";
    }

    public static void runInTransaction(Connection conn, SqlRunnable body) throws SQLException {
        boolean previous = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            body.run();
            conn.commit();
        } catch (Exception e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                e.addSuppressed(rollbackEx);
            }
            if (e instanceof SQLException sqlEx) {
                throw sqlEx;
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new SQLException(e);
        } finally {
            conn.setAutoCommit(previous);
        }
    }

    public static Set<String> selectExistingKeys(Connection conn, DbTable table, List<String> keyColumns,
            Collection<String> candidateKeys, TableQuoter tableQuoter, ColumnQuoter columnQuoter) throws SQLException {
        Set<String> existing = new LinkedHashSet<>();
        if (candidateKeys == null || candidateKeys.isEmpty() || keyColumns == null || keyColumns.isEmpty()) {
            return existing;
        }
        List<String> keys = new ArrayList<>(candidateKeys);
        for (int start = 0; start < keys.size(); start += IN_BATCH_SIZE) {
            List<String> batch = keys.subList(start, Math.min(start + IN_BATCH_SIZE, keys.size()));
            StringBuilder sql = new StringBuilder("SELECT ");
            for (int i = 0; i < keyColumns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(columnQuoter.quote(keyColumns.get(i)));
            }
            sql.append(" FROM ").append(tableQuoter.quote(table)).append(" t WHERE ");
            SqlSubsetSupport.appendPkInClause(sql, "t", keyColumns, batch.size(), columnQuoter);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                bindKeys(ps, 1, table, keyColumns, batch);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        existing.add(SqlSubsetSupport.readPkKey(rs, keyColumns));
                    }
                }
            }
        }
        return existing;
    }

    public static Map<String, Object[]> selectExistingRows(Connection conn, DbTable table, List<String> keyColumns,
            Collection<String> candidateKeys, TableQuoter tableQuoter, ColumnQuoter columnQuoter) throws SQLException {
        Map<String, Object[]> result = new LinkedHashMap<>();
        if (candidateKeys == null || candidateKeys.isEmpty() || keyColumns == null || keyColumns.isEmpty()) {
            return result;
        }
        DbColumn[] ordered = table.orderedColumns();
        List<String> keys = new ArrayList<>(candidateKeys);
        for (int start = 0; start < keys.size(); start += IN_BATCH_SIZE) {
            List<String> batch = keys.subList(start, Math.min(start + IN_BATCH_SIZE, keys.size()));
            StringBuilder sql = new StringBuilder("SELECT ");
            for (int i = 0; i < ordered.length; i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(columnQuoter.quote(ordered[i].getName()));
            }
            sql.append(" FROM ").append(tableQuoter.quote(table)).append(" t WHERE ");
            SqlSubsetSupport.appendPkInClause(sql, "t", keyColumns, batch.size(), columnQuoter);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                bindKeys(ps, 1, table, keyColumns, batch);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Object[] values = new Object[ordered.length];
                        for (int i = 0; i < ordered.length; i++) {
                            values[i] = rs.getObject(i + 1);
                        }
                        Object[] matchParts = new Object[keyColumns.size()];
                        boolean skip = false;
                        for (int i = 0; i < keyColumns.size(); i++) {
                            int idx = UpsertPlanner.columnIndex(table, keyColumns.get(i));
                            if (idx < 0 || idx >= values.length || values[idx] == null) {
                                skip = true;
                                break;
                            }
                            matchParts[i] = values[idx];
                        }
                        if (!skip) {
                            result.put(encodeKey(matchParts), values);
                        }
                    }
                }
            }
        }
        return result;
    }

    public static Map<String, String> selectUniqueToMatchKey(Connection conn, DbTable table,
            List<String> uniqueColumns, List<String> matchColumns, Collection<String> uniqueKeys,
            TableQuoter tableQuoter, ColumnQuoter columnQuoter) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        if (uniqueKeys == null || uniqueKeys.isEmpty()) {
            return result;
        }
        List<String> keys = new ArrayList<>(uniqueKeys);
        List<String> selectCols = new ArrayList<>(uniqueColumns);
        for (String match : matchColumns) {
            if (!selectCols.contains(match)) {
                selectCols.add(match);
            }
        }
        for (int start = 0; start < keys.size(); start += IN_BATCH_SIZE) {
            List<String> batch = keys.subList(start, Math.min(start + IN_BATCH_SIZE, keys.size()));
            StringBuilder sql = new StringBuilder("SELECT ");
            for (int i = 0; i < selectCols.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(columnQuoter.quote(selectCols.get(i)));
            }
            sql.append(" FROM ").append(tableQuoter.quote(table)).append(" t WHERE ");
            SqlSubsetSupport.appendPkInClause(sql, "t", uniqueColumns, batch.size(), columnQuoter);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                bindKeys(ps, 1, table, uniqueColumns, batch);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Object[] uniqueParts = new Object[uniqueColumns.size()];
                        Object[] matchParts = new Object[matchColumns.size()];
                        for (int i = 0; i < uniqueColumns.size(); i++) {
                            uniqueParts[i] = rs.getObject(indexOf(selectCols, uniqueColumns.get(i)) + 1);
                        }
                        for (int i = 0; i < matchColumns.size(); i++) {
                            matchParts[i] = rs.getObject(indexOf(selectCols, matchColumns.get(i)) + 1);
                        }
                        result.put(encodeKey(uniqueParts), encodeKey(matchParts));
                    }
                }
            }
        }
        return result;
    }

    public static int insertRows(Connection conn, DbTable table, List<UpsertRow> rows, List<String> omitColumns,
            TableQuoter tableQuoter, ColumnQuoter columnQuoter) throws SQLException {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<DbColumn> writeColumns = writeColumns(table, omitColumns);
        if (writeColumns.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableQuoter.quote(table)).append(" (");
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < writeColumns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
                params.append(", ");
            }
            sql.append(columnQuoter.quote(writeColumns.get(i).getName()));
            params.append("?");
        }
        sql.append(") VALUES (").append(params).append(")");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (UpsertRow row : rows) {
                for (int i = 0; i < writeColumns.size(); i++) {
                    bindColumn(ps, i + 1, table, writeColumns.get(i), row.getValues());
                }
                ps.addBatch();
            }
            return ps.executeBatch().length;
        }
    }

    public static int updateRows(Connection conn, DbTable table, List<UpsertRow> rows, UpsertMatchKey matchKey,
            List<String> omitColumns, TableQuoter tableQuoter, ColumnQuoter columnQuoter) throws SQLException {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        Set<String> skip = new LinkedHashSet<>();
        if (omitColumns != null) {
            skip.addAll(omitColumns);
        }
        skip.addAll(matchKey.getColumns());
        List<DbColumn> setColumns = writeColumns(table, skip);
        if (setColumns.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(tableQuoter.quote(table)).append(" SET ");
        for (int i = 0; i < setColumns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(columnQuoter.quote(setColumns.get(i).getName())).append("=?");
        }
        sql.append(" WHERE ");
        List<String> matchCols = matchKey.getColumns();
        for (int i = 0; i < matchCols.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(columnQuoter.quote(matchCols.get(i))).append("=?");
        }
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (UpsertRow row : rows) {
                int idx = 1;
                for (DbColumn column : setColumns) {
                    bindColumn(ps, idx++, table, column, row.getValues());
                }
                for (String matchCol : matchCols) {
                    int colIdx = UpsertPlanner.columnIndex(table, matchCol);
                    Object value = colIdx >= 0 && colIdx < row.getValues().length ? row.getValues()[colIdx] : null;
                    if (value == null) {
                        ps.setNull(idx++, Types.NULL);
                    } else {
                        ps.setObject(idx++, SqlSubsetSupport.coercePkValue(
                            SqlSubsetSupport.canonicalPkPart(value), table.column(matchCol)));
                    }
                }
                ps.addBatch();
            }
            return ps.executeBatch().length;
        }
    }

    public static List<String> previewPlan(UpsertPlan plan, TableQuoter tableQuoter, ColumnQuoter columnQuoter) {
        List<String> statements = new ArrayList<>();
        if (plan == null || plan.getTables() == null) {
            return statements;
        }
        for (UpsertTablePlan tablePlan : plan.getTables()) {
            statements.addAll(previewInserts(tablePlan.getTable(), tablePlan.getInserts(),
                tablePlan.getOmitColumns(), tableQuoter, columnQuoter));
            statements.addAll(previewUpdates(tablePlan.getTable(), tablePlan.getUpdates(),
                tablePlan.getMatchKey(), tablePlan.getOmitColumns(), tableQuoter, columnQuoter));
        }
        return statements;
    }

    static List<String> previewInserts(DbTable table, List<UpsertRow> rows, List<String> omitColumns,
            TableQuoter tableQuoter, ColumnQuoter columnQuoter) {
        List<String> statements = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return statements;
        }
        List<DbColumn> writeColumns = writeColumns(table, omitColumns);
        if (writeColumns.isEmpty()) {
            return statements;
        }
        StringBuilder head = new StringBuilder("INSERT INTO ").append(tableQuoter.quote(table)).append(" (");
        for (int i = 0; i < writeColumns.size(); i++) {
            if (i > 0) {
                head.append(", ");
            }
            head.append(columnQuoter.quote(writeColumns.get(i).getName()));
        }
        head.append(") VALUES (");
        for (UpsertRow row : rows) {
            StringBuilder sql = new StringBuilder(head);
            for (int i = 0; i < writeColumns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(columnLiteral(table, writeColumns.get(i), row.getValues()));
            }
            sql.append(")");
            statements.add(sql.toString());
        }
        return statements;
    }

    static List<String> previewUpdates(DbTable table, List<UpsertRow> rows, UpsertMatchKey matchKey,
            List<String> omitColumns, TableQuoter tableQuoter, ColumnQuoter columnQuoter) {
        List<String> statements = new ArrayList<>();
        if (rows == null || rows.isEmpty() || matchKey == null) {
            return statements;
        }
        Set<String> skip = new LinkedHashSet<>();
        if (omitColumns != null) {
            skip.addAll(omitColumns);
        }
        skip.addAll(matchKey.getColumns());
        List<DbColumn> setColumns = writeColumns(table, skip);
        if (setColumns.isEmpty()) {
            return statements;
        }
        List<String> matchCols = matchKey.getColumns();
        for (UpsertRow row : rows) {
            StringBuilder sql = new StringBuilder("UPDATE ").append(tableQuoter.quote(table)).append(" SET ");
            for (int i = 0; i < setColumns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                DbColumn column = setColumns.get(i);
                sql.append(columnQuoter.quote(column.getName())).append("=")
                    .append(columnLiteral(table, column, row.getValues()));
            }
            sql.append(" WHERE ");
            for (int i = 0; i < matchCols.size(); i++) {
                if (i > 0) {
                    sql.append(" AND ");
                }
                String matchCol = matchCols.get(i);
                sql.append(columnQuoter.quote(matchCol)).append("=")
                    .append(columnLiteral(table, table.column(matchCol), row.getValues()));
            }
            statements.add(sql.toString());
        }
        return statements;
    }

    private static String columnLiteral(DbTable table, DbColumn column, Object[] values) {
        if (column == null) {
            return "NULL";
        }
        int colIdx = UpsertPlanner.columnIndex(table, column.getName());
        Object value = colIdx >= 0 && values != null && colIdx < values.length ? values[colIdx] : null;
        return SqlSubsetSupport.sqlLiteral(value, column);
    }

    public static void applyPlan(Connection conn, UpsertPlan plan, TableQuoter tableQuoter, ColumnQuoter columnQuoter)
            throws SQLException {
        if (plan.getTables() == null) {
            return;
        }
        for (UpsertTablePlan tablePlan : plan.getTables()) {
            insertRows(conn, tablePlan.getTable(), tablePlan.getInserts(), tablePlan.getOmitColumns(),
                tableQuoter, columnQuoter);
            updateRows(conn, tablePlan.getTable(), tablePlan.getUpdates(), tablePlan.getMatchKey(),
                tablePlan.getOmitColumns(), tableQuoter, columnQuoter);
        }
    }

    public static UpsertTargetLookup lookup(Connection conn, TableQuoter tableQuoter, ColumnQuoter columnQuoter) {
        return new UpsertTargetLookup() {
            @Override
            public Set<String> existingKeys(DbTable table, List<String> keyColumns, Collection<String> candidateKeys) {
                try {
                    return selectExistingKeys(conn, table, keyColumns, candidateKeys, tableQuoter, columnQuoter);
                } catch (SQLException e) {
                    throw Exceptions.server("upsert-lookup-failed")
                        .withExtra("table", table.qualifiedName())
                        .withCause(e)
                        .get();
                }
            }

            @Override
            public Map<String, String> uniqueToMatchKey(DbTable table, List<String> uniqueColumns,
                    List<String> matchColumns, Collection<String> uniqueKeys) {
                try {
                    return selectUniqueToMatchKey(conn, table, uniqueColumns, matchColumns, uniqueKeys,
                        tableQuoter, columnQuoter);
                } catch (SQLException e) {
                    throw Exceptions.server("upsert-unique-lookup-failed")
                        .withExtra("table", table.qualifiedName())
                        .withCause(e)
                        .get();
                }
            }

            @Override
            public Map<String, Object[]> existingRows(DbTable table, List<String> keyColumns,
                    Collection<String> candidateKeys) {
                try {
                    return selectExistingRows(conn, table, keyColumns, candidateKeys, tableQuoter, columnQuoter);
                } catch (SQLException e) {
                    throw Exceptions.server("upsert-row-lookup-failed")
                        .withExtra("table", table.qualifiedName())
                        .withCause(e)
                        .get();
                }
            }
        };
    }

    static int bindKeys(PreparedStatement ps, int startIndex, DbTable table, List<String> keyColumns, List<String> keys)
            throws SQLException {
        int idx = startIndex;
        for (String key : keys) {
            String[] parts = SqlSubsetSupport.splitPkKey(key, keyColumns.size());
            for (int c = 0; c < keyColumns.size(); c++) {
                DbColumn column = table.column(keyColumns.get(c));
                Object typed = SqlSubsetSupport.coercePkValue(parts[c], column);
                if (typed == null) {
                    ps.setNull(idx++, Types.NULL);
                } else {
                    ps.setObject(idx++, typed);
                }
            }
        }
        return idx;
    }

    private static List<DbColumn> writeColumns(DbTable table, Collection<String> omitColumns) {
        Set<String> omit = omitColumns == null ? Set.of() : new LinkedHashSet<>(omitColumns);
        List<DbColumn> columns = new ArrayList<>();
        for (DbColumn column : table.orderedColumns()) {
            if (!omit.contains(column.getName())) {
                columns.add(column);
            }
        }
        return columns;
    }

    private static void bindColumn(PreparedStatement ps, int parameterIndex, DbTable table, DbColumn column,
            Object[] values) throws SQLException {
        int colIdx = UpsertPlanner.columnIndex(table, column.getName());
        Object value = colIdx >= 0 && values != null && colIdx < values.length ? values[colIdx] : null;
        if (value == null) {
            ps.setNull(parameterIndex, Types.NULL);
        } else {
            ps.setObject(parameterIndex, value);
        }
    }

    private static int indexOf(List<String> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equals(name)) {
                return i;
            }
        }
        return 0;
    }
}
