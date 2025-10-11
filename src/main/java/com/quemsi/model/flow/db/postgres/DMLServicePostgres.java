package com.quemsi.model.flow.db.postgres;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.postgresql.jdbc.PgArray;
import org.postgresql.jdbc.PgClob;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataPage;
import com.quemsi.model.flow.in.TableDataPage.Request;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class DMLServicePostgres implements DMLService{
    private static final String GET_TABLE_DATA_PAGE_FORMAT = "select * from %s t order by %s limit ? offset ?";
	
    private DataSource dataSource;

    @Override
    public TableDataPage getTableDataPage(Request request) {
        try(Connection conn = dataSource.getConnection()){
			String sql = String.format(GET_TABLE_DATA_PAGE_FORMAT, request.getTable().getName(), request.getTable().joinedPkColumnNames());
			log.info("sql for {} :{} offset :{} count: {}", request.getTable().getName(), sql, request.getPageNum() * request.getPageSize(), request.getPageSize());
			PreparedStatement ps = conn.prepareStatement(sql);
			ps.setInt(1, request.getPageSize());
			ps.setInt(2, request.getPageNum() * request.getPageSize());
			
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
				for(String columnName : request.getTable().columnNames()){
					if(!request.getTable().getPkColumnNames().contains(columnName)){
						Object val = rs.getObject(columnName);
						if(val instanceof PgArray pgArray){
							val = pgArray.getArray();
						} else if (val instanceof PgClob pgClob){
							val = pgClob.toString();
						}
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
    public int writePageData(DbTable table, DataPage dataPage) {
        try(Connection conn = dataSource.getConnection()){
			StringBuilder sqlBuilder = new StringBuilder("insert into ").append(table.getName()).append("(");
			StringBuilder paramsBuilder = new StringBuilder("(");
			int counter = 0;
 			for(String columnName : table.columnNames()){
				sqlBuilder.append(columnName);
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
					if(e.getValue()[i] instanceof List listVal){
						Array arrVal = conn.createArrayOf("varchar", listVal.toArray());
						ps.setArray(i + 1, arrVal);
					} else if(e.getValue()[i] instanceof Map mapVal){
						if("tsvector".equals(mapVal.get("type"))){
							ps.setString(i + 1, (String)mapVal.get("value"));
						}
					}
					else{
						ps.setObject(i + 1, e.getValue()[i], java.sql.Types.OTHER);
					}
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
    public void close() throws Exception {
    }

}
