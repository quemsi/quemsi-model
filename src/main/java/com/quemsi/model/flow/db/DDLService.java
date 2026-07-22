package com.quemsi.model.flow.db;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.diff.DbModelDiff;

public interface DDLService extends AutoCloseable {
    boolean dropTables(String...  tableNames);
	boolean dropSequences(String... sequenceNames);
	boolean dropViews(String... viewNames);
	void disableConstraints(Set<ReferenceInfo> constraints);
	void enableContraints(Set<ReferenceInfo> constraints);
	void createTables(DbModel dbModel);
	/** Create/replace routines required by views (e.g. Postgres functions). No-op when unsupported. */
	void createFunctions(DbModel dbModel);
	void createViews(DbModel dbModel);
	/** Create triggers after data load. No-op when unsupported. */
	default void createTriggers(DbModel dbModel) {
	}
	/**
	 * Create full-text catalogs/indexes after data load and before CONTAINS/FREETEXT routines/views.
	 * No-op when unsupported.
	 */
	default void createFullTextIndexes(DbModel dbModel) {
	}
	/** Drop routines (procedures/functions). No-op when unsupported. */
	default boolean dropFunctions(DbModel dbModel) {
		return true;
	}
	/** Drop triggers. No-op when unsupported. */
	default boolean dropTriggers(DbModel dbModel) {
		return true;
	}
	/** Drop domain types (e.g. after DROP TABLE when rebuilding). No-op when unsupported. */
	default boolean dropDomains(String... domainNames) {
		return true;
	}
	/** Drop enum/user types. No-op when unsupported. */
	default boolean dropEnumTypes(String... typeNames) {
		return true;
	}
	boolean checkSchema(String schema) throws SQLException;
	
	/**
	 * Converts a DbModelDiff to a list of SQL statements.
	 * 
	 * @param diff The database model diff containing operations to convert
	 * @return List of SQL statements as strings
	 */
	List<String> ddlFrom(DbModelDiff diff, DbModel dbModel);
	
	/**
	 * Executes a single SQL statement.
	 * 
	 * @param sql The SQL statement to execute
	 * @throws SQLException if the SQL statement execution fails
	 */
	void executeSql(String sql) throws SQLException;
}
