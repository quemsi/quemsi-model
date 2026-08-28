package com.quemsi.model.flow.upsert;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
                SqlUpsertSupport.columnQuoter(DatasourceType.MYSQL));
            SqlUpsertSupport.updateRows(recording.connection, table, updates, matchKey, List.of(),
                SqlUpsertSupport.tableQuoter(DatasourceType.MYSQL),
                SqlUpsertSupport.columnQuoter(DatasourceType.MYSQL));
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
                SqlUpsertSupport.columnQuoter(DatasourceType.MYSQL))));

        assertThat(recording.rollbackCalls.get(), equalTo(1));
        assertThat(recording.commitCalls.get(), equalTo(0));
        assertThat(recording.autoCommit.get(), equalTo(true));
    }

    @Test
    void encodeKeyUsesCanonicalParts() {
        assertThat(SqlUpsertSupport.encodeKey("TR"), equalTo("TR"));
        assertThat(SqlUpsertSupport.encodeKey(1, "TR"),
            equalTo("1" + com.quemsi.model.flow.db.DataSourceFactory.PK_VALUES_SEPERATOR + "TR"));
        assertThat(SqlUpsertSupport.encodeKey(10), equalTo("10"));
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
