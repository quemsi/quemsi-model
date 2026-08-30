package com.quemsi.model.flow.upsert;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbTable;

class SqlUpsertSupportTest {

    @Test
    void insertAndUpdateBatchAndCommit() throws Exception {
        RecordingConnection recording = new RecordingConnection(false);
        DbTable table = messageTable();
        UpsertMatchKey matchKey = UpsertMatchKey.builder()
            .columns(List.of("message_key"))
            .primaryKey(true)
            .source("PRIMARY KEY")
            .build();
        List<UpsertRow> inserts = List.of(new UpsertRow("new-key", new Object[] { "new-key", "New" }));
        List<UpsertRow> updates = List.of(new UpsertRow("existing", new Object[] { "existing", "Updated" }));

        SqlUpsertSupport.runInTransaction(recording.connection, () -> {
            SqlUpsertSupport.insertRows(recording.connection, table, inserts, List.of(),
                SqlUpsertSupport.tableQuoter(DatasourceType.MYSQL),
                SqlUpsertSupport.columnQuoter(DatasourceType.MYSQL),
                DatasourceType.MYSQL);
            SqlUpsertSupport.updateRows(recording.connection, table, updates, matchKey, List.of(),
                SqlUpsertSupport.tableQuoter(DatasourceType.MYSQL),
                SqlUpsertSupport.columnQuoter(DatasourceType.MYSQL),
                DatasourceType.MYSQL);
        });

        assertThat(recording.preparedSql, hasItem(containsString("INSERT INTO")));
        assertThat(recording.preparedSql, hasItem(containsString("UPDATE")));
        assertThat(recording.executeBatchCalls.get(), equalTo(2));
        assertThat(recording.commitCalls.get(), equalTo(1));
        assertThat(recording.rollbackCalls.get(), equalTo(0));
        assertThat(recording.autoCommit.get(), equalTo(true));
    }

    @Test
    void batchFailureRollsBack() throws Exception {
        RecordingConnection recording = new RecordingConnection(true);
        DbTable table = messageTable();
        List<UpsertRow> inserts = List.of(new UpsertRow("a", new Object[] { "a", "A" }));

        assertThrows(SQLException.class, () -> SqlUpsertSupport.runInTransaction(recording.connection, () ->
            SqlUpsertSupport.insertRows(recording.connection, table, inserts, List.of(),
                SqlUpsertSupport.tableQuoter(DatasourceType.MYSQL),
                SqlUpsertSupport.columnQuoter(DatasourceType.MYSQL),
                DatasourceType.MYSQL)));

        assertThat(recording.rollbackCalls.get(), equalTo(1));
        assertThat(recording.commitCalls.get(), equalTo(0));
        assertThat(recording.autoCommit.get(), equalTo(true));
    }

    @Test
    void postgresBindsJsonbAndPointAsOther() throws Exception {
        RecordingConnection recording = new RecordingConnection(false);
        DbTable table = airportsTable();
        UpsertMatchKey matchKey = UpsertMatchKey.builder()
            .columns(List.of("airport_code"))
            .primaryKey(true)
            .source("PRIMARY KEY")
            .build();
        List<UpsertRow> updates = List.of(new UpsertRow("YKS", new Object[] {
            "YKS",
            "{\"en\": \"Yakutsk Airport\", \"ru\": \"Якутск\"}",
            "{\"en\": \"Yakutsk\", \"ru\": \"Якутск\"}",
            "(129.77099609375,62.093299865722656)",
            "Asia/Yakutsk"
        }));

        SqlUpsertSupport.updateRows(recording.connection, table, updates, matchKey, List.of(),
            SqlUpsertSupport.tableQuoter(DatasourceType.POSTGRES),
            SqlUpsertSupport.columnQuoter(DatasourceType.POSTGRES),
            DatasourceType.POSTGRES);

        assertThat(recording.setObjectWithSqlType, hasSize(5));
        assertThat(recording.setObjectWithSqlType, everyItem(equalTo(Types.OTHER)));
        assertThat(recording.setObjectWithoutSqlType, hasSize(0));
    }

    @Test
    void encodeKeyUsesCanonicalParts() {
        assertThat(SqlUpsertSupport.encodeKey("TR"), equalTo("TR"));
        assertThat(SqlUpsertSupport.encodeKey(1, "TR"),
            equalTo("1" + com.quemsi.model.flow.db.DataSourceFactory.PK_VALUES_SEPERATOR + "TR"));
        assertThat(SqlUpsertSupport.encodeKey(10), equalTo("10"));
    }

    @Test
    void previewPlanRendersInsertAndUpdateSql() {
        DbTable table = messageTable();
        UpsertMatchKey matchKey = UpsertMatchKey.builder()
            .columns(List.of("message_key"))
            .primaryKey(true)
            .source("PRIMARY KEY")
            .build();
        UpsertPlan plan = UpsertPlan.builder()
            .tables(List.of(UpsertTablePlan.builder()
                .qualifiedName("validation_message")
                .table(table)
                .matchKey(matchKey)
                .inserts(List.of(new UpsertRow("new-key", new Object[] { "new-key", "New" })))
                .updates(List.of(new UpsertRow("existing", new Object[] { "existing", "O'Reilly" })))
                .build()))
            .build();

        List<String> sql = SqlUpsertSupport.previewPlan(plan,
            SqlUpsertSupport.tableQuoter(DatasourceType.POSTGRES),
            SqlUpsertSupport.columnQuoter(DatasourceType.POSTGRES));

        assertThat(sql, hasSize(2));
        assertThat(sql.get(0), equalTo(
            "INSERT INTO \"validation_message\" (\"message_key\", \"message_value\") VALUES ('new-key', 'New')"));
        assertThat(sql.get(1), equalTo(
            "UPDATE \"validation_message\" SET \"message_value\"='O''Reilly' WHERE \"message_key\"='existing'"));
    }

    private static DbTable airportsTable() {
        DbTable table = new DbTable("bookings", "airports_data");
        table.addColumn(DbColumn.builder().name("airport_code").dataType("bpchar").columnType("character(3)")
            .ordinalPosition(1).nullable(false).build());
        table.addColumn(DbColumn.builder().name("airport_name").dataType("jsonb").columnType("jsonb")
            .ordinalPosition(2).nullable(false).build());
        table.addColumn(DbColumn.builder().name("city").dataType("jsonb").columnType("jsonb")
            .ordinalPosition(3).nullable(false).build());
        table.addColumn(DbColumn.builder().name("coordinates").dataType("point").columnType("point")
            .ordinalPosition(4).nullable(false).build());
        table.addColumn(DbColumn.builder().name("timezone").dataType("text").columnType("text")
            .ordinalPosition(5).nullable(false).build());
        table.getPkColumnNames().add("airport_code");
        return table;
    }

    private static DbTable messageTable() {
        DbTable table = new DbTable(null, "validation_message");
        table.addColumn(DbColumn.builder().name("message_key").dataType("varchar").columnType("varchar(100)")
            .ordinalPosition(1).nullable(false).build());
        table.addColumn(DbColumn.builder().name("message_value").dataType("varchar").columnType("varchar(255)")
            .ordinalPosition(2).nullable(true).build());
        table.getPkColumnNames().add("message_key");
        return table;
    }

    private static final class RecordingConnection {
        final List<String> preparedSql = new ArrayList<>();
        final List<Integer> setObjectWithSqlType = new ArrayList<>();
        final List<Object> setObjectWithoutSqlType = new ArrayList<>();
        final AtomicInteger commitCalls = new AtomicInteger();
        final AtomicInteger rollbackCalls = new AtomicInteger();
        final AtomicInteger executeBatchCalls = new AtomicInteger();
        final AtomicBoolean autoCommit = new AtomicBoolean(true);
        final boolean failOnExecuteBatch;
        final Connection connection;

        RecordingConnection(boolean failOnExecuteBatch) {
            this.failOnExecuteBatch = failOnExecuteBatch;
            this.connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                this::invokeConnection);
        }

        private Object invokeConnection(Object proxy, Method method, Object[] args) throws SQLException {
            return switch (method.getName()) {
                case "getAutoCommit" -> autoCommit.get();
                case "setAutoCommit" -> {
                    autoCommit.set((Boolean) args[0]);
                    yield null;
                }
                case "commit" -> {
                    commitCalls.incrementAndGet();
                    yield null;
                }
                case "rollback" -> {
                    if (args == null || args.length == 0) {
                        rollbackCalls.incrementAndGet();
                    }
                    yield null;
                }
                case "prepareStatement" -> {
                    preparedSql.add((String) args[0]);
                    yield preparedStatementProxy();
                }
                case "createStatement" -> statementProxy();
                case "close" -> null;
                case "isClosed" -> false;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "RecordingConnection";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }

        private Statement statementProxy() {
            return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[] { Statement.class },
                (proxy, method, args) -> {
                    if ("close".equals(method.getName()) || "isClosed".equals(method.getName())) {
                        return "isClosed".equals(method.getName()) ? false : null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        }

        private PreparedStatement preparedStatementProxy() {
            return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("setObject".equals(name)) {
                        if (args != null && args.length >= 3 && args[2] instanceof Integer sqlType) {
                            setObjectWithSqlType.add(sqlType);
                        } else if (args != null && args.length >= 2) {
                            setObjectWithoutSqlType.add(args[1]);
                        }
                        return null;
                    }
                    if (name.startsWith("set") || "addBatch".equals(name) || "close".equals(name)) {
                        return null;
                    }
                    if ("isClosed".equals(name)) {
                        return false;
                    }
                    if ("executeBatch".equals(name)) {
                        executeBatchCalls.incrementAndGet();
                        if (failOnExecuteBatch) {
                            throw new SQLException("batch failed");
                        }
                        return new int[] { 1 };
                    }
                    if ("executeQuery".equals(name)) {
                        return emptyResultSet();
                    }
                    if ("equals".equals(name)) {
                        return proxy == args[0];
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("toString".equals(name)) {
                        return "RecordingPreparedStatement";
                    }
                    throw new UnsupportedOperationException(name);
                });
        }

        private ResultSet emptyResultSet() {
            return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        return false;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        }
    }
}
