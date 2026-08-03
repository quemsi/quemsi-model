package com.quemsi.model.flow.subset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.util.CommonHelpers;

/**
 * Shared JDBC helpers for subset seed / parent-closure / keyed page reads.
 */
public final class SqlSubsetSupport {
    public static final int IN_BATCH_SIZE = 500;

    @FunctionalInterface
    public interface TableQuoter {
        String quote(DbTable table);
    }

    @FunctionalInterface
    public interface ColumnQuoter {
        String quote(String columnName);
    }

    public enum LimitStyle {
        MYSQL_LIMIT,
        POSTGRES_LIMIT,
        SQLSERVER_TOP,
        ORACLE_FETCH
    }

    private SqlSubsetSupport() {
    }

    public static String whereClause(String whereFragment) {
        if (StringUtils.isEmptyOrNull(whereFragment)) {
            return "";
        }
        return " WHERE (" + whereFragment.trim() + ")";
    }

    public static long countRows(Connection conn, DbTable table, String whereFragment,
            TableQuoter tableQuoter) throws SQLException {
        SubsetPredicateValidator.validate(whereFragment);
        String sql = "SELECT COUNT(*) FROM " + tableQuoter.quote(table) + " " + SubsetPredicateValidator.TABLE_ALIAS
            + whereClause(whereFragment);
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        }
    }

    public static Set<String> selectPrimaryKeys(Connection conn, DbTable table, String whereFragment, Integer limit,
            TableQuoter tableQuoter, ColumnQuoter columnQuoter, LimitStyle limitStyle) throws SQLException {
        requirePrimaryKey(table);
        SubsetPredicateValidator.validate(whereFragment);
        List<String> pkCols = table.getPkColumnNames();
        String pkSelect = pkCols.stream().map(c -> SubsetPredicateValidator.TABLE_ALIAS + "." + columnQuoter.quote(c))
            .collect(Collectors.joining(", "));
        String orderBy = pkCols.stream().map(c -> SubsetPredicateValidator.TABLE_ALIAS + "." + columnQuoter.quote(c))
            .collect(Collectors.joining(", "));
        String from = tableQuoter.quote(table) + " " + SubsetPredicateValidator.TABLE_ALIAS;
        String where = whereClause(whereFragment);
        String sql = buildSelectPkSql(pkSelect, from, where, orderBy, limit, limitStyle);
        Set<String> keys = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                keys.add(readPkKey(rs, pkCols));
            }
        }
        return keys;
    }

    private static String buildSelectPkSql(String pkSelect, String from, String where, String orderBy,
            Integer limit, LimitStyle limitStyle) {
        if (limit == null || limit <= 0) {
            return "SELECT " + pkSelect + " FROM " + from + where + " ORDER BY " + orderBy;
        }
        return switch (limitStyle) {
            case MYSQL_LIMIT -> "SELECT " + pkSelect + " FROM " + from + where + " ORDER BY " + orderBy
                + " LIMIT " + limit;
            case POSTGRES_LIMIT -> "SELECT " + pkSelect + " FROM " + from + where + " ORDER BY " + orderBy
                + " LIMIT " + limit;
            case SQLSERVER_TOP -> "SELECT TOP (" + limit + ") " + pkSelect + " FROM " + from + where
                + " ORDER BY " + orderBy;
            case ORACLE_FETCH -> "SELECT " + pkSelect + " FROM " + from + where + " ORDER BY " + orderBy
                + " FETCH FIRST " + limit + " ROWS ONLY";
        };
    }

    /**
     * For selected child rows, resolve referenced parent primary keys via an FK.
     */
    public static Set<String> selectParentPrimaryKeys(Connection conn, DbTable child, DbTable parent,
            List<String> childFkColumns, List<String> parentRefColumns, Collection<String> childPkKeys,
            TableQuoter tableQuoter, ColumnQuoter columnQuoter) throws SQLException {
        requirePrimaryKey(child);
        requirePrimaryKey(parent);
        if (childFkColumns == null || childFkColumns.isEmpty() || parentRefColumns == null
                || parentRefColumns.size() != childFkColumns.size()) {
            throw Exceptions.badRequest("subset-fk-invalid")
                .withExtra("child", child.qualifiedName())
                .withExtra("parent", parent.qualifiedName())
                .get();
        }
        if (childPkKeys == null || childPkKeys.isEmpty()) {
            return Set.of();
        }
        Set<String> parentKeys = new LinkedHashSet<>();
        List<String> childPkCols = child.getPkColumnNames();
        List<String> parentPkCols = parent.getPkColumnNames();
        List<String> keyBatch = new ArrayList<>(childPkKeys);
        for (int start = 0; start < keyBatch.size(); start += IN_BATCH_SIZE) {
            List<String> batch = keyBatch.subList(start, Math.min(start + IN_BATCH_SIZE, keyBatch.size()));
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT DISTINCT ");
            sql.append(parentPkCols.stream()
                .map(c -> "p." + columnQuoter.quote(c))
                .collect(Collectors.joining(", ")));
            sql.append(" FROM ").append(tableQuoter.quote(parent)).append(" p");
            sql.append(" INNER JOIN ").append(tableQuoter.quote(child)).append(" c ON ");
            for (int i = 0; i < childFkColumns.size(); i++) {
                if (i > 0) {
                    sql.append(" AND ");
                }
                sql.append("c.").append(columnQuoter.quote(childFkColumns.get(i)))
                    .append(" = p.").append(columnQuoter.quote(parentRefColumns.get(i)));
            }
            sql.append(" WHERE ");
            appendPkInClause(sql, "c", childPkCols, batch.size(), columnQuoter);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                bindPkKeys(ps, 1, childPkCols.size(), batch);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        parentKeys.add(readPkKey(rs, parentPkCols));
                    }
                }
            }
        }
        return parentKeys;
    }

    public static void appendPkInClause(StringBuilder sql, String alias, List<String> pkCols, int keyCount,
            ColumnQuoter columnQuoter) {
        if (pkCols.size() == 1) {
            sql.append(alias).append(".").append(columnQuoter.quote(pkCols.get(0))).append(" IN (");
            for (int i = 0; i < keyCount; i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append('?');
            }
            sql.append(')');
            return;
        }
        sql.append('(');
        for (int i = 0; i < keyCount; i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append('(');
            for (int c = 0; c < pkCols.size(); c++) {
                if (c > 0) {
                    sql.append(" AND ");
                }
                sql.append(alias).append('.').append(columnQuoter.quote(pkCols.get(c))).append("=?");
            }
            sql.append(')');
        }
        sql.append(')');
    }

    public static int bindPkKeys(PreparedStatement ps, int startIndex, int pkArity, List<String> keys)
            throws SQLException {
        int idx = startIndex;
        for (String key : keys) {
            String[] parts = splitPkKey(key, pkArity);
            for (String part : parts) {
                ps.setObject(idx++, part);
            }
        }
        return idx;
    }

    public static String readPkKey(ResultSet rs, List<String> pkCols) throws SQLException {
        if (pkCols.size() == 1) {
            Object v = rs.getObject(1);
            return v == null ? "" : v.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) {
                sb.append(DataSourceFactory.PK_VALUES_SEPERATOR);
            }
            Object v = rs.getObject(i + 1);
            sb.append(v == null ? "" : v.toString());
        }
        return sb.toString();
    }

    public static String[] splitPkKey(String key, int arity) {
        if (arity <= 1) {
            return new String[] { key };
        }
        String sep = DataSourceFactory.PK_VALUES_SEPERATOR;
        String[] parts = new String[arity];
        int from = 0;
        for (int i = 0; i < arity - 1; i++) {
            int at = key.indexOf(sep, from);
            if (at < 0) {
                throw Exceptions.badRequest("subset-pk-key-invalid")
                    .withExtra("key", key)
                    .withExtra("arity", arity)
                    .get();
            }
            parts[i] = key.substring(from, at);
            from = at + sep.length();
        }
        parts[arity - 1] = key.substring(from);
        return parts;
    }

    public static void requirePrimaryKey(DbTable table) {
        if (CommonHelpers.isEmptyOrNull(table.getPkColumnNames())) {
            throw Exceptions.badRequest("subset-table-requires-pk")
                .withExtra("table", table.qualifiedName())
                .get();
        }
    }

    public static List<String> pageKeys(List<String> orderedKeys, int pageNum, int pageSize) {
        int from = pageNum * pageSize;
        if (from >= orderedKeys.size()) {
            return List.of();
        }
        int to = Math.min(from + pageSize, orderedKeys.size());
        return orderedKeys.subList(from, to);
    }

    public static String buildKeyedSelectSql(DbTable table, String selectList, String orderBy,
            int keyCount, TableQuoter tableQuoter, ColumnQuoter columnQuoter) {
        requirePrimaryKey(table);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(selectList)
            .append(" FROM ").append(tableQuoter.quote(table)).append(' ')
            .append(SubsetPredicateValidator.TABLE_ALIAS)
            .append(" WHERE ");
        appendPkInClause(sql, SubsetPredicateValidator.TABLE_ALIAS, table.getPkColumnNames(), keyCount, columnQuoter);
        sql.append(" ORDER BY ").append(orderBy);
        return sql.toString();
    }
}
