package com.quemsi.model.flow.db.sqlserver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataPage;
import com.quemsi.model.flow.in.TableDataPage.Request;
import com.quemsi.model.util.CommonHelpers;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class DMLServiceSqlserver implements DMLService{
    private static final String GET_TABLE_DATA_PAGE_FORMAT = """
select * from (
	select *, ROW_NUMBER() OVER (ORDER BY %s ) AS RowNum from %s
) q WHERE q.RowNum > ? AND q.RowNum <= (? + ?)
;
            """;;
	private static final String SET_INSERT_IDENTITY_ON = "SET IDENTITY_INSERT %s ON;";
	private static final String SET_INSERT_IDENTITY_OFF = "SET IDENTITY_INSERT %s OFF;";
	private static final String GET_MAX_COLUMN_VALUE_SQL = "SELECT MAX([%s]) as max_val FROM %s";
	private static final String UPDATE_SEQUENCE_SQL = "ALTER SEQUENCE %s RESTART WITH %d";

    private DataSource dataSource;
	private ReentrantLock globalLock;

    @Override
    public TableDataPage getTableDataPage(Request request){
		try(Connection conn = dataSource.getConnection()){
			String sortColumnNames = !StringUtils.isEmptyOrNull(request.getTable().getPkColumnNames()) ?request.getTable().getPkColumnNames().stream().map(c -> "[" + c + "]").collect(Collectors.joining(", ")) : request.getTable().getColumns().keySet().stream().map(c -> "[" + c + "]").collect(Collectors.joining(", "));
			String sql = String.format(GET_TABLE_DATA_PAGE_FORMAT, sortColumnNames, request.getTable().qualifiedName());
			log.info("sql for {} :{} offset :{} count: {}", request.getTable().qualifiedName(), sql, request.getPageNum() * request.getPageSize(), request.getPageSize());
			PreparedStatement ps = conn.prepareStatement(sql);
			ps.setInt(1, request.getPageNum() * request.getPageSize());
			ps.setInt(2, request.getPageNum() * request.getPageSize());
			ps.setInt(3, request.getPageSize());
			
			TableDataPage page = new TableDataPage();
			page.setRequest(request);
			String pkNames = request.getTable().joinedPkColumnNames();
			List<String> pkColumnNames = request.getTable().getPkColumnNames();
			List<String> allColumnNames = new ArrayList<>(request.getTable().columnNames());
			int numberOfColumns = allColumnNames.size();
			if(CommonHelpers.isEmptyOrNull(request.getTable().getPkColumnNames())){
				pkNames = "[RowNum]";
				pkColumnNames = List.of("RowNum");
				allColumnNames.add("RowNum");
				numberOfColumns++;
			}

			Map<Object, Object[]> tableData = new HashMap<>();
			ResultSet rs = ps.executeQuery();
			while(rs.next()){
				Object[] cellValues = new Object[numberOfColumns];
				int columnIndex = 0;
				Object pk = null;
				StringBuilder pkBuilder = new StringBuilder();
				Map<String, Object> pkVals = new HashMap<>();
				log.trace("{} pk {} for row {}", request.getTable().getName(), pkNames, pk);
				for(String columnName : allColumnNames){
					if(!pkColumnNames.contains(columnName)){
						Object val = rs.getObject(columnName);
						log.trace("{} column {} value {}", request.getTable().getName(), columnName, val);
						cellValues[columnIndex++] = val;
					}else{
						if(pkColumnNames.size() == 1){
							String pkName = pkColumnNames.iterator().next();
							pk = rs.getObject(pkName);
							cellValues[columnIndex++] = pk;
						}else{
							Object pkVal = Exceptions.wrapSupplier(() -> rs.getObject(columnName)).get();
							cellValues[columnIndex++] = pkVal;
							pkVals.put(columnName, pkVal);
							if(pkBuilder.length() > 0){
								pkBuilder.append(DataSourceFactory.PK_VALUES_SEPERATOR);
							}
							pkBuilder.append(pkVal.toString());
						}
					}
				}
				if(pk == null){
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
	private String escape(String columnName){
		StringBuilder sb = new StringBuilder();
		if(DatasourceFactorySqlserver.RESERVED_KEYS.contains(columnName.toUpperCase())){
			sb.append("[").append(columnName).append("]");	
		}else{
			sb.append(columnName);
		}
		return sb.toString();
	}
    @Override
    public int writePageData(DbTable table, DataPage dataPage){
		try(Connection conn = dataSource.getConnection()){
			StringBuilder sqlBuilder = new StringBuilder("insert into ").append(table.qualifiedName()).append("(");
			StringBuilder paramsBuilder = new StringBuilder("(");
			int counter = 0;
 			for(String columnName : table.columnNames()){
				sqlBuilder.append("[").append(escape(columnName)).append("]");
				paramsBuilder.append("?");
				counter++;
				if(counter < table.columnNames().size()){
					sqlBuilder.append(", ");
					paramsBuilder.append(", ");
				}
			}
			paramsBuilder.append(");");
			sqlBuilder.append(") values ").append(paramsBuilder.toString());
			String insertSql = sqlBuilder.toString();
			DbColumn[] orderedColumns = table.orderedColumns();
			boolean hasIdentity = Arrays.stream(orderedColumns).map(c -> c.isIdentity()).reduce(Boolean.FALSE, (c, v) -> c || v);
			if(hasIdentity){
				// globalLock.lock();
				String setIdentityOnSql = String.format(SET_INSERT_IDENTITY_ON, table.qualifiedName());
				String setIdentityOffSql = String.format(SET_INSERT_IDENTITY_OFF, table.qualifiedName());
				insertSql = setIdentityOnSql + insertSql + setIdentityOffSql;
			}
			log.info("for {} insert sql :{}", table.getName(), insertSql);
			PreparedStatement ps = conn.prepareStatement(insertSql);
			dataPage.getData().entrySet().forEach(Exceptions.wrapConsumer(e -> {
				for(int i=0; i < orderedColumns.length; i++){
					DbColumn c = orderedColumns[i];	
					if("varbinary".equals(c.getColumnType())){
						if(e.getValue()[i] == null){
							ps.setNull(i + 1, Types.VARBINARY);
						}else{
							String encodedStr = (String)e.getValue()[i];
							byte[] decodedBin = Base64.getDecoder().decode(encodedStr);
							ps.setBytes(i + 1, decodedBin);
						}
					} else{
						ps.setObject(i + 1, e.getValue()[i]);
					}
				}
				ps.addBatch();
			}));
			int[] results = ps.executeBatch();
			log.info("for {} page {} batch result size {}", table.getName(), dataPage.getPageNum(), results.length);
		}catch(Exception e){
			e.printStackTrace();
			throw Exceptions.server("unable-to-write-data").withExtra("table", table.getName()) .withExtra("pageNum", dataPage.getPageNum()).withCause(e).get();
		}finally{
			if(globalLock != null && globalLock.isHeldByCurrentThread()){
				globalLock.unlock();
			}
		}
		return 0;		
	}

    @Override
	public boolean clearTables(String... tableNames) {
		try(Connection conn = dataSource.getConnection()){
			Statement s = conn.createStatement();
			for(String tableName : tableNames){
				s.addBatch("delete from " + tableName);
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
        String sql = String.format(GET_MAX_COLUMN_VALUE_SQL, columnName, qualifiedTableName);
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
			PreparedStatement ps = conn.prepareStatement(String.format(UPDATE_SEQUENCE_SQL, qualifiedSequenceName, newValue))) {
			ps.executeUpdate();
		} catch (SQLException e) {
			log.warn("Error updating sequence {} to value {}", qualifiedSequenceName, newValue, e);
		}
    }

    @Override
    public void close() throws Exception {
    }
}
