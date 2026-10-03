package com.quemsi.model.flow.db.cross;

import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.sql.DbColumn;

public interface TargetTypeWriter {
	DatasourceType target();

	void apply(DbColumn column, PortableColumn portable, CrossDbReport report);

	String btreeIndexType();

	int maxNumericPrecision();

	int maxCharLength();
}
