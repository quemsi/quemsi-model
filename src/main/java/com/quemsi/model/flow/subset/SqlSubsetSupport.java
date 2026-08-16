package com.quemsi.model.flow.subset;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbColumn;
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
                bindPkKeys(ps, 1, child, batch);
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

    /**
     * Binds canonical string PK keys as native JDBC types inferred from {@link DbTable} PK columns.
     */
    public static int bindPkKeys(PreparedStatement ps, int startIndex, DbTable table, List<String> keys)
            throws SQLException {
        requirePrimaryKey(table);
        List<String> pkCols = table.getPkColumnNames();
        int idx = startIndex;
        for (String key : keys) {
            String[] parts = splitPkKey(key, pkCols.size());
            for (int c = 0; c < pkCols.size(); c++) {
                DbColumn column = table.column(pkCols.get(c));
                Object typed = coercePkValue(parts[c], column);
                if (typed == null) {
                    ps.setNull(idx++, Types.NULL);
                } else {
                    ps.setObject(idx++, typed);
                }
            }
        }
        return idx;
    }

    /**
     * Converts a canonical string PK part into a JDBC-friendly native value for the column type.
     * Keeps plan keys as strings for set membership while avoiding varchar/bigint mismatches.
     */
    public static Object coercePkValue(String part, DbColumn column) {
        if (part == null || part.isEmpty()) {
            return null;
        }
        String type = normalizeSqlType(column);
        if (type == null || type.isBlank()) {
            return inferNumericOrString(part);
        }
        return switch (type) {
            case "BIGINT", "INT8", "BIGSERIAL", "SERIAL8", "LONG" -> Long.valueOf(part);
            case "NUMBER" -> {
                if (column != null && column.getNumScale() != null && column.getNumScale() > 0) {
                    yield new BigDecimal(part);
                }
                yield coerceIntegerLike(part);
            }
            case "INTEGER", "INT", "INT4", "SERIAL", "SERIAL4", "MEDIUMINT", "SMALLINT", "INT2", "TINYINT",
                    "SMALLSERIAL", "SERIAL2" -> coerceIntegerLike(part);
            case "NUMERIC", "DECIMAL", "MONEY", "SMALLMONEY" -> new BigDecimal(part);
            case "REAL", "FLOAT4", "FLOAT", "DOUBLE", "DOUBLE PRECISION", "FLOAT8" -> Double.valueOf(part);
            case "BOOLEAN", "BOOL", "BIT" -> coerceBoolean(part);
            case "UUID", "UNIQUEIDENTIFIER" -> UUID.fromString(part);
            case "DATE" -> coerceDate(part);
            case "TIMESTAMP", "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITHOUT TIME ZONE",
                    "DATETIME", "DATETIME2", "SMALLDATETIME", "DATETIMEOFFSET" -> coerceTimestamp(part);
            default -> part;
        };
    }

    private static Object coerceIntegerLike(String part) {
        try {
            long v = Long.parseLong(part);
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                return (int) v;
            }
            return v;
        } catch (NumberFormatException e) {
            return new BigInteger(part);
        }
    }

    private static Object inferNumericOrString(String part) {
        try {
            if (part.indexOf('.') >= 0 || part.indexOf('e') >= 0 || part.indexOf('E') >= 0) {
                return new BigDecimal(part);
            }
            return coerceIntegerLike(part);
        } catch (NumberFormatException e) {
            return part;
        }
    }

    private static Boolean coerceBoolean(String part) {
        String p = part.trim();
        if ("t".equalsIgnoreCase(p) || "true".equalsIgnoreCase(p) || "1".equals(p) || "y".equalsIgnoreCase(p)) {
            return Boolean.TRUE;
        }
        if ("f".equalsIgnoreCase(p) || "false".equalsIgnoreCase(p) || "0".equals(p) || "n".equalsIgnoreCase(p)) {
            return Boolean.FALSE;
        }
        return Boolean.valueOf(p);
    }

    /** Parses ISO date or timestamp-like strings (Oracle DATE often round-trips as {@code yyyy-MM-dd HH:mm:ss.S}). */
    private static Object coerceDate(String part) {
        String p = part.trim();
        try {
            return Date.valueOf(LocalDate.parse(p));
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return Date.valueOf(LocalDateTime.parse(p.replace(' ', 'T')).toLocalDate());
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return Date.valueOf(Timestamp.valueOf(p.replace('T', ' ')).toLocalDateTime().toLocalDate());
        } catch (Exception ignored) {
            // fall through
        }
        if (p.length() >= 10 && p.charAt(4) == '-' && p.charAt(7) == '-') {
            try {
                return Date.valueOf(p.substring(0, 10));
            } catch (Exception ignored) {
                // fall through
            }
        }
        return part;
    }

    private static Object coerceTimestamp(String part) {
        try {
            return Timestamp.valueOf(LocalDateTime.parse(part));
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return Timestamp.from(OffsetDateTime.parse(part).toInstant());
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return Timestamp.valueOf(part.replace('T', ' '));
        } catch (Exception e) {
            try {
                return new Timestamp(Date.valueOf(LocalDate.parse(part.trim().substring(0, Math.min(10, part.trim().length())))).getTime());
            } catch (Exception ignored) {
                return part;
            }
        }
    }

    private static String normalizeSqlType(DbColumn column) {
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

    public static String readPkKey(ResultSet rs, List<String> pkCols) throws SQLException {
        if (pkCols.size() == 1) {
            Object v = rs.getObject(1);
            return canonicalPkPart(v);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) {
                sb.append(DataSourceFactory.PK_VALUES_SEPERATOR);
            }
            Object v = rs.getObject(i + 1);
            sb.append(canonicalPkPart(v));
        }
        return sb.toString();
    }

    /** Stable string form for set membership; must round-trip via {@link #coercePkValue}. */
    static String canonicalPkPart(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Double d) {
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        if (v instanceof Float f) {
            return BigDecimal.valueOf(f.doubleValue()).stripTrailingZeros().toPlainString();
        }
        if (v instanceof Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof Timestamp ts) {
            LocalDateTime ldt = ts.toLocalDateTime();
            if (ldt.getHour() == 0 && ldt.getMinute() == 0 && ldt.getSecond() == 0 && ldt.getNano() == 0) {
                return ldt.toLocalDate().toString();
            }
            return ldt.toString();
        }
        if (v instanceof LocalDate ld) {
            return ld.toString();
        }
        if (v instanceof LocalDateTime ldt) {
            if (ldt.getHour() == 0 && ldt.getMinute() == 0 && ldt.getSecond() == 0 && ldt.getNano() == 0) {
                return ldt.toLocalDate().toString();
            }
            return ldt.toString();
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.toString();
        }
        if (v instanceof byte[] bytes) {
            return java.util.Base64.getEncoder().encodeToString(bytes);
        }
        // Oracle JDBC may return oracle.sql.TIMESTAMP / DATE — normalize via toString then date prefix.
        String asText = v.toString();
        if (asText != null && asText.length() >= 10 && asText.charAt(4) == '-' && asText.charAt(7) == '-'
                && (asText.length() == 10 || asText.charAt(10) == ' ' || asText.charAt(10) == 'T')) {
            String time = asText.length() > 10 ? asText.substring(11) : "";
            if (time.isEmpty() || time.startsWith("00:00:00")) {
                return asText.substring(0, 10);
            }
        }
        return asText;
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

    public static final int BROWSE_DEFAULT_LIMIT = 50;
    public static final int BROWSE_MAX_LIMIT = 200;
    /** Non-PK display columns included in browse grid. */
    public static final int BROWSE_EXTRA_COLUMNS = 8;

    public static int normalizeBrowseLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return BROWSE_DEFAULT_LIMIT;
        }
        return Math.min(limit, BROWSE_MAX_LIMIT);
    }

    /**
     * Sample rows for the subset builder grid. PK columns first, then up to
     * {@link #BROWSE_EXTRA_COLUMNS} other columns. Empty/null where = no filter.
     */
    public static SubsetBrowseResult browseRows(Connection conn, DbTable table, String whereFragment, Integer limit,
            TableQuoter tableQuoter, ColumnQuoter columnQuoter, LimitStyle limitStyle) throws SQLException {
        requirePrimaryKey(table);
        if (!StringUtils.isEmptyOrNull(whereFragment)) {
            SubsetPredicateValidator.validate(whereFragment);
        }
        int rowLimit = normalizeBrowseLimit(limit);
        List<String> pkCols = table.getPkColumnNames();
        List<String> displayCols = new ArrayList<>(pkCols);
        for (DbColumn col : table.orderedColumns()) {
            if (col == null || col.getName() == null) {
                continue;
            }
            if (pkCols.contains(col.getName())) {
                continue;
            }
            displayCols.add(col.getName());
            if (displayCols.size() >= pkCols.size() + BROWSE_EXTRA_COLUMNS) {
                break;
            }
        }
        String selectList = displayCols.stream()
            .map(c -> SubsetPredicateValidator.TABLE_ALIAS + "." + columnQuoter.quote(c))
            .collect(Collectors.joining(", "));
        String orderBy = pkCols.stream()
            .map(c -> SubsetPredicateValidator.TABLE_ALIAS + "." + columnQuoter.quote(c))
            .collect(Collectors.joining(", "));
        String from = tableQuoter.quote(table) + " " + SubsetPredicateValidator.TABLE_ALIAS;
        String where = whereClause(whereFragment);
        String sql = buildSelectPkSql(selectList, from, where, orderBy, rowLimit, limitStyle);

        List<SubsetBrowseResult.BrowseRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String pkKey = readPkKey(rs, pkCols);
                List<String> values = new ArrayList<>(displayCols.size());
                for (int i = 0; i < displayCols.size(); i++) {
                    Object v = rs.getObject(i + 1);
                    values.add(v == null ? "" : canonicalPkPart(v));
                }
                rows.add(SubsetBrowseResult.BrowseRow.builder().pkKey(pkKey).values(values).build());
            }
        }
        return SubsetBrowseResult.builder().columns(displayCols).rows(rows).build();
    }

    /**
     * Builds a validated WHERE fragment for selected primary keys (alias {@code t}).
     * Single-column PK uses {@code IN (...)}; composite uses OR of AND equalities.
     */
    public static String buildPkInPredicate(DbTable table, Collection<String> selectedKeys) {
        requirePrimaryKey(table);
        if (selectedKeys == null || selectedKeys.isEmpty()) {
            throw Exceptions.badRequest("subset-pk-selection-required")
                .withExtra("table", table.qualifiedName())
                .get();
        }
        List<String> pkCols = table.getPkColumnNames();
        String alias = SubsetPredicateValidator.TABLE_ALIAS;
        StringBuilder sb = new StringBuilder();
        if (pkCols.size() == 1) {
            DbColumn column = table.column(pkCols.get(0));
            sb.append(alias).append('.').append(pkCols.get(0)).append(" IN (");
            boolean first = true;
            for (String key : selectedKeys) {
                if (key == null) {
                    continue;
                }
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(sqlLiteral(key, column));
            }
            sb.append(')');
        } else {
            boolean firstKey = true;
            for (String key : selectedKeys) {
                if (key == null) {
                    continue;
                }
                if (!firstKey) {
                    sb.append(" OR ");
                }
                firstKey = false;
                String[] parts = splitPkKey(key, pkCols.size());
                sb.append('(');
                for (int c = 0; c < pkCols.size(); c++) {
                    if (c > 0) {
                        sb.append(" AND ");
                    }
                    DbColumn column = table.column(pkCols.get(c));
                    sb.append(alias).append('.').append(pkCols.get(c)).append(" = ")
                        .append(sqlLiteral(parts[c], column));
                }
                sb.append(')');
            }
        }
        String predicate = sb.toString();
        if (predicate.isBlank()) {
            throw Exceptions.badRequest("subset-pk-selection-required")
                .withExtra("table", table.qualifiedName())
                .get();
        }
        SubsetPredicateValidator.validate(predicate);
        return predicate;
    }

    static String sqlLiteral(String canonicalPart, DbColumn column) {
        Object typed = coercePkValue(canonicalPart, column);
        if (typed == null) {
            return "NULL";
        }
        if (typed instanceof Number || typed instanceof Boolean) {
            return typed.toString();
        }
        if (typed instanceof UUID uuid) {
            return "'" + uuid + "'";
        }
        if (typed instanceof Date d) {
            return "'" + d.toLocalDate() + "'";
        }
        if (typed instanceof Timestamp ts) {
            return "'" + ts.toLocalDateTime() + "'";
        }
        if (typed instanceof LocalDate ld) {
            return "'" + ld + "'";
        }
        if (typed instanceof LocalDateTime ldt) {
            return "'" + ldt + "'";
        }
        if (typed instanceof OffsetDateTime odt) {
            return "'" + odt + "'";
        }
        String text = typed.toString();
        return "'" + text.replace("'", "''") + "'";
    }
}
