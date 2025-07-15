package com.quemsi.model.flow.db;

import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataPage;

public interface DMLService extends AutoCloseable{
    TableDataPage getTableDataPage(TableDataPage.Request request);
	int writePageData(DbTable table, DataPage dataPage);
	boolean clearTables(String... tableNames);
}
