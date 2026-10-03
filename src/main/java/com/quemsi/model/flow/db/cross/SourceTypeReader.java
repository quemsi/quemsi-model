package com.quemsi.model.flow.db.cross;

import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;

public interface SourceTypeReader {
	DatasourceType source();

	ColumnMapping read(DbColumn column, DbModel model, CrossDbReport report);
}
