package com.quemsi.model.flow.db.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.quemsi.commons.util.Exceptions;
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
public class DMLServiceMysql implements DMLService{
    private static final String GET_TABLE_DATA_PAGE_FORMAT = "select * from %s t order by %s limit ?, ?";
	private static final String GET_MAX_COLUMN_VALUE_SQL = "SELECT MAX(`%s`) as max_val FROM %s";
	private DataSource dataSource;

    @Override
    public TableDataPage getTableDataPage(Request request){
		try(Connection conn = dataSource.getConnection()){
			String sortColumnNames = !CommonHelpers.isEmptyOrNull(request.getTable().getPkColumnNames()) ? request.getTable().getPkColumnNames().stream().map(c -> "`" + c + "`").collect(Collectors.joining(", ")) : request.getTable().getColumns().keySet().stream().map(c -> "`" + c + "`").collect(Collectors.joining(", "));
			String sql = String.format(GET_TABLE_DATA_PAGE_FORMAT, request.getTable().getName(), sortColumnNames);
			log.info("sql for {} :{} offset :{} count: {}", request.getTable().getName(), sql, request.getPageNum() * request.getPageSize(), request.getPageSize());
			PreparedStatement ps = conn.prepareStatement(sql);
			ps.setInt(1, request.getPageNum() * request.getPageSize());
			ps.setInt(2, request.getPageSize());
			
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
						log.trace("{} column {} value {}", request.getTable().getName(), columnName, val);
						cellValues[columnIndex++] = val;
					}else{
						if(request.getTable().getPkColumnNames().size() == 1){
							String pkName = request.getTable().getPkColumnNames().iterator().next();
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

    @Override
    public int writePageData(DbTable table, DataPage dataPage){
		try(Connection conn = dataSource.getConnection()){
			StringBuilder sqlBuilder = new StringBuilder("insert into ").append(table.getName()).append("(");
			StringBuilder paramsBuilder = new StringBuilder("(");
			int counter = 0;
 			for(String columnName : table.columnNames()){
				sqlBuilder.append("`").append(columnName).append("`");
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
			log.info("for {} insert sql :{}", table.getName(), insertSql);
			DbColumn[] orderedColumns = table.orderedColumns();
			PreparedStatement ps = conn.prepareStatement(insertSql);
			dataPage.getData().entrySet().forEach(Exceptions.wrapConsumer(e -> {
				for(int i=0; i < orderedColumns.length; i++){
					DbColumn c = orderedColumns[i];	
					ps.setObject(c.getOrdinalPosition(), e.getValue()[i]);
				}
				ps.addBatch();
			}));
			int[] results = ps.executeBatch();
			log.info("for {} page {} batch result {}", table.getName(), dataPage.getPageNum(), results);
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
        try (Statement stmt = dataSource.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                Object maxVal = rs.getObject("max_val");
                if (maxVal != null) {
                    if (maxVal instanceof Number) {
                        return ((Number) maxVal).longValue();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error getting max value for column {} in table {}", columnName, qualifiedTableName, e);
        }
        return null;
	}
	@Override
	public void updateSequence(String qualifiedSequenceName, Long newValue) {
		throw Exceptions.server("not-supported-implemented").get();
	}
    @Override
    public void close() throws Exception {
        
    }

}
