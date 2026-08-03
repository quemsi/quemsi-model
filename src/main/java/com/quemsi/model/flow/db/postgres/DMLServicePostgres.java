package com.quemsi.model.flow.db.postgres;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.postgresql.jdbc.PgArray;
import org.postgresql.jdbc.PgClob;
import org.postgresql.util.PGobject;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.CustomSerializedColumn;
import com.quemsi.model.flow.in.TableDataPage;
import com.quemsi.model.flow.in.TableDataPage.Request;
import com.quemsi.model.flow.subset.SqlSubsetSupport;
import com.quemsi.model.flow.subset.SqlSubsetSupport.LimitStyle;
import com.quemsi.model.util.CommonHelpers;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class DMLServicePostgres implements DMLService{
	private static final String GET_TABLE_DATA_PAGE_FORMAT = "select * from %s t order by %s limit ? offset ?";
	private static final String GET_MAX_COLUMN_VALUE_SQL = "SELECT MAX(%s) as max_val FROM %s";
	private static final String UPDATE_SEQUENCE_SQL = "SELECT setval(?, ?, false)";

	private DataSource dataSource;

	static String quotedTable(DbTable table) {
		return CommonHelpers.doubleQuotedQualified(table.getSchema(), table.getName());
	}

	static String quotedColumn(String columnName) {
		return CommonHelpers.doubleQuoted(columnName);
	}

	@Override
	public boolean supportsSubset() {
		return true;
	}

	@Override
	public int getTablePageSize(Integer expectedPageSize, DbTable table) {
		return expectedPageSize != null && expectedPageSize > 0 ? expectedPageSize : 1000;
	}

	@Override
	public long countRows(DbTable table) {
		String from = quotedTable(table);
		try (Connection conn = dataSource.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(String.format("SELECT COUNT(*) FROM %s", from))) {
			if (rs.next()) {
				return rs.getLong(1);
			}
			return 0L;
		} catch (SQLException e) {
			throw Exceptions.server("unable-to-count-rows")
				.withExtra("table", table.qualifiedName())
				.withCause(e)
				.get();
		}
	}

	@Override
	public long countRows(DbTable table, String whereFragment) {
		try (Connection conn = dataSource.getConnection()) {
			return SqlSubsetSupport.countRows(conn, table, whereFragment, DMLServicePostgres::quotedTable);
		} catch (SQLException e) {
			throw Exceptions.server("unable-to-count-rows")
				.withExtra("table", table.qualifiedName())
				.withCause(e)
				.get();
		}
	}

	@Override
	public Set<String> selectPrimaryKeys(DbTable table, String whereFragment, Integer limit) {
		try (Connection conn = dataSource.getConnection()) {
			return SqlSubsetSupport.selectPrimaryKeys(conn, table, whereFragment, limit,
				DMLServicePostgres::quotedTable, DMLServicePostgres::quotedColumn, LimitStyle.POSTGRES_LIMIT);
		} catch (SQLException e) {
			throw Exceptions.server("unable-to-select-primary-keys")
				.withExtra("table", table.qualifiedName())
				.withCause(e)
				.get();
		}
	}

	@Override
	public Set<String> selectParentPrimaryKeys(DbTable child, DbTable parent,
			List<String> childFkColumns, List<String> parentRefColumns, Collection<String> childPkKeys) {
		try (Connection conn = dataSource.getConnection()) {
			return SqlSubsetSupport.selectParentPrimaryKeys(conn, child, parent, childFkColumns, parentRefColumns,
				childPkKeys, DMLServicePostgres::quotedTable, DMLServicePostgres::quotedColumn);
		} catch (SQLException e) {
			throw Exceptions.server("unable-to-select-parent-keys")
				.withExtra("child", child.qualifiedName())
				.withExtra("parent", parent.qualifiedName())
				.withCause(e)
				.get();
		}
	}

    @Override
    public TableDataPage getTableDataPage(Request request) {
        try(Connection conn = dataSource.getConnection()){
			String sortColumnNames;
			if (!CommonHelpers.isEmptyOrNull(request.getTable().getPkColumnNames())) {
				sortColumnNames = request.getTable().getPkColumnNames().stream().map(CommonHelpers::doubleQuoted).collect(Collectors.joining(", "));
			} else {
				List<String> orderable = request.getTable().orderableColumnNames();
				sortColumnNames = orderable.isEmpty()
					? "(SELECT NULL)"
					: orderable.stream().map(CommonHelpers::doubleQuoted).collect(Collectors.joining(", "));
			}
			String from = quotedTable(request.getTable());
			PreparedStatement ps;
			List<String> primaryKeys = request.getPrimaryKeys();
			if (primaryKeys != null && !primaryKeys.isEmpty()) {
				String orderBy = request.getTable().getPkColumnNames().stream()
					.map(c -> "t." + quotedColumn(c)).collect(Collectors.joining(", "));
				String sql = SqlSubsetSupport.buildKeyedSelectSql(request.getTable(), "t.*", orderBy,
					primaryKeys.size(), DMLServicePostgres::quotedTable, DMLServicePostgres::quotedColumn);
				log.info("subset sql for {} :{} keys={}", from, sql, primaryKeys.size());
				ps = conn.prepareStatement(sql);
				SqlSubsetSupport.bindPkKeys(ps, 1, request.getTable(), primaryKeys);
			} else {
				String sql = String.format(GET_TABLE_DATA_PAGE_FORMAT, from, sortColumnNames);
				log.info("sql for {} :{} offset :{} count: {}", from, sql, request.getPageNum() * request.getPageSize(), request.getPageSize());
				ps = conn.prepareStatement(sql);
				ps.setInt(1, request.getPageSize());
				ps.setInt(2, request.getPageNum() * request.getPageSize());
			}
			TableDataPage page = new TableDataPage();
			page.setRequest(request);
			
			Map<Object, Object[]> tableData = new HashMap<>();
			ResultSet rs = ps.executeQuery();
			while(rs.next()){
				Object[] cellValues = new Object[request.getTable().getColumns().size()];
				int columnIndex = 0;
				Object pk = null;
				StringBuilder pkBuilder = new StringBuilder();
				Map<String, Object> pkVals = new HashMap<>();
				log.trace("{} pk {} for row {}", request.getTable().getName(), request.getTable().joinedPkColumnNames(), pk);
				if(CommonHelpers.isEmptyOrNull(request.getTable().getPkColumnNames())){
					pk = request.getSeqGenerator().getAndIncrement();
				}
				for(String columnName : request.getTable().columnNames()){
					if(!request.getTable().getPkColumnNames().contains(columnName)){
						Object val = rs.getObject(columnName);
						if(val instanceof PgArray pgArray){
							val = pgArray.getArray();
						} else if (val instanceof PgClob pgClob){
							val = pgClob.toString();
						} else if (val instanceof PGobject pGobject){
							val = pGobject.getValue();
						} else if (!rs.wasNull() && request.getTable().column(columnName).getColumnType().equals("oid")){
							long oid = rs.getLong(columnName);
							byte[] data = null;
							if(!rs.wasNull()){
								data = getBinaryData(conn, oid);
							}
							if(data != null){
								val = CustomSerializedColumn.BinaryColumn.builder().dbType(request.getTable().column(columnName).getColumnType()).dataId(Long.toString(oid))
									.data(data)
									.build();
							}else{
								val = null;
							}
						}
						log.trace("{} column {} value {}", request.getTable().getName(), columnName, val);
						cellValues[columnIndex++] = val;
					}else{
						if(request.getTable().getPkColumnNames().size() == 1){
							String pkName = request.getTable().getPkColumnNames().iterator().next();
							pk = rs.getObject(pkName);
							if(pk instanceof PGobject pGobject){
								pk = pGobject.getValue();
							}
							cellValues[columnIndex++] = pk;
						}else{
							Object pkVal = Exceptions.wrapSupplier(() -> rs.getObject(columnName)).get();
							if(pkVal instanceof PGobject pGobject){
								pkVal = pGobject.getValue();
							}
							cellValues[columnIndex++] = pkVal;
							pkVals.put(columnName, pkVal);
							if(pkBuilder.length() > 0){
								pkBuilder.append(DataSourceFactory.PK_VALUES_SEPERATOR);
							}
							pkBuilder.append(pkVal.toString());
						}
					}
				}
				if(request.getTable().getPkColumnNames().size() > 1){
					pk = pkBuilder.toString();
				}
				tableData.put(pk, cellValues);
			}
			page.setTableData(tableData);
			page.setHasMorePage(page.getTableData().size() >= request.getPageSize());
			log.info("{} page for {} created", request.getPageNum(), request.getTable().getName());
			return page;
		}catch(Exception e){
			e.printStackTrace();
			throw Exceptions.server("unable-to-read-data").withExtra("request", request).withCause(e).get();
		}
    }

	private byte[] getBinaryData(Connection conn, Long oid){
		try(PreparedStatement ps = conn.prepareStatement("SELECT lo_get(" + oid + ")")){
			ResultSet rs = ps.executeQuery();
			if(rs.next()){
				return rs.getBytes(1);
			}			
		}catch(Exception e){
			log.info("unable to get binary data for oid {}", oid, e);
		}
		return null;
	}

    @Override
    public int writePageData(DbTable table, DataPage dataPage) {
        try(Connection conn = dataSource.getConnection()){
			boolean previousAutoCommit = conn.getAutoCommit();
			try {
				conn.setAutoCommit(false);
				StringBuilder sqlBuilder = new StringBuilder("insert into ").append(quotedTable(table)).append("(");
				StringBuilder paramsBuilder = new StringBuilder("(");
				int counter = 0;
				for(String columnName : table.columnNames()){
					sqlBuilder.append(CommonHelpers.doubleQuoted(columnName));
					if(table.column(columnName).getColumnType().equals("oid")){
						paramsBuilder.append("lo_from_bytea(0, ?)");
					}else{
						paramsBuilder.append("?");
					}
					counter++;
					if(counter < table.columnNames().size()){
						sqlBuilder.append(", ");
						paramsBuilder.append(", ");
					}
				}
				/* No trailing ';' — reWriteBatchedInserts rewrites PreparedStatement batches */
				paramsBuilder.append(")");
				sqlBuilder.append(") values ").append(paramsBuilder.toString());
				String insertSql = sqlBuilder.toString();
				log.info("for {} insert sql :{}", table.getName(), insertSql);
				DbColumn[] orderedColumns = table.orderedColumns();
				PreparedStatement ps = conn.prepareStatement(insertSql);
				dataPage.getData().entrySet().forEach(Exceptions.wrapConsumer(e -> {
					for(int i=0; i < orderedColumns.length; i++){
						if(e.getValue()[i] == null){
							ps.setNull(i + 1, java.sql.Types.NULL);
						} else if(e.getValue()[i] instanceof List listVal){
							Array arrVal = conn.createArrayOf("varchar", listVal.toArray());
							ps.setArray(i + 1, arrVal);
						} else if(e.getValue()[i] instanceof Map mapVal){
							if("tsvector".equals(mapVal.get("type"))){
								ps.setString(i + 1, (String)mapVal.get("value"));
							} else if("oid".equals(mapVal.get("dbType"))){
								String encodedData = (String)mapVal.get("data");
								byte[] binaryData = Base64.getDecoder().decode(encodedData);
								ps.setBytes(i + 1, binaryData);
							} else{
								log.error("type {} is not a valid map type", mapVal.get("type"));
								throw Exceptions.server("column-type-not-supported").withExtra("table", table.getName()).withExtra("columnIndex", i + 1).withExtra("column", table.column(orderedColumns[i].getName())).withExtra("value", mapVal).get();
							}
						} else if(e.getValue()[i] instanceof CustomSerializedColumn serializedColumn){
							ps.setBytes(i + 1, serializedColumn.getData());
						} else{
							ps.setObject(i + 1, e.getValue()[i], java.sql.Types.OTHER);
						}
					}
					ps.addBatch();
				}));
				int[] results = ps.executeBatch();
				conn.commit();
				log.info("for {} page {} batch inserted {} rows", table.getName(), dataPage.getPageNum(), results.length);
			} catch (Exception e) {
				try {
					conn.rollback();
				} catch (SQLException rollbackEx) {
					log.warn("rollback failed for table {} page {}", table.getName(), dataPage.getPageNum(), rollbackEx);
				}
				throw e;
			} finally {
				try {
					conn.setAutoCommit(previousAutoCommit);
				} catch (SQLException autoCommitEx) {
					log.warn("failed to restore autocommit for table {} page {}", table.getName(), dataPage.getPageNum(), autoCommitEx);
				}
			}
		}catch(Exception e){
			e.printStackTrace();
			throw Exceptions.server("unable-to-write-data").withExtra("table", table.getName()) .withExtra("pageNum", dataPage.getPageNum()).withCause(e).get();
		}
		return 0;
    }

    @Override
    public boolean clearTables(String... tableNames) {
        try(Connection conn = dataSource.getConnection()){
			Statement s = conn.createStatement();
			for(String tableName : tableNames){
				s.addBatch("delete from " + CommonHelpers.doubleQuotedQualified(tableName));
			}
			s.executeBatch();
			return true;
		}catch(Exception e){
			e.printStackTrace();
			throw Exceptions.server("failed-to-clear-tables").withCause(e).get();
		}
    }

	@Override
	public Long getMaxColumnValue(String qualifiedTableName, String columnName) {
        String sql = String.format(GET_MAX_COLUMN_VALUE_SQL,
			CommonHelpers.doubleQuoted(columnName),
			CommonHelpers.doubleQuotedQualified(qualifiedTableName));
        try (Connection conn = dataSource.getConnection();
			Statement stmt = conn.createStatement();) {
			ResultSet rs = stmt.executeQuery(sql);
            if (rs.next()) {
                Object maxVal = rs.getObject("max_val");
                if (maxVal != null) {
                    if (maxVal instanceof Number) {
                        return ((Number) maxVal).longValue();
                    }
                }
            }
			rs.close();
        } catch (Exception e) {
            log.warn("Error getting max value for column {} in table {}", columnName, qualifiedTableName, e);
        }
        return null;
    }
	@Override
    public void updateSequence(String qualifiedSequenceName, Long newValue) {
		try (Connection conn = dataSource.getConnection();
		PreparedStatement ps = conn.prepareStatement(UPDATE_SEQUENCE_SQL)) {
			/* setval(regclass) needs a quoted identity for mixed-case sequence names */
			ps.setString(1, CommonHelpers.doubleQuotedQualified(qualifiedSequenceName));
			ps.setLong(2, newValue);
			ps.executeQuery();
		} catch (SQLException e) {
			log.warn("Error updating sequence {} to value {}", qualifiedSequenceName, newValue, e);
		}
    }

    @Override
    public void close() throws Exception {
    }

}
