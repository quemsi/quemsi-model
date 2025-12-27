package com.quemsi.model.flow.db;

import java.sql.SQLException;
import java.util.Set;

import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;

public interface DDLService extends AutoCloseable {
    boolean dropTables(String...  tableNames);
	boolean dropSequences(String... sequenceNames);
	void disableConstraints(Set<ReferenceInfo> constraints);
	void enableContraints(Set<ReferenceInfo> constraints);
	void createTables(DbModel dbModel);
	boolean checkSchema(String schema) throws SQLException;
}
