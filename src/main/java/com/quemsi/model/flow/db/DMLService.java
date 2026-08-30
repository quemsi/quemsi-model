package com.quemsi.model.flow.db;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataPage;
import com.quemsi.model.flow.subset.SubsetBrowseResult;

public interface DMLService extends AutoCloseable{
    int getTablePageSize(Integer expectedPageSize, DbTable table);
    /** Exact (or estimated for Mongo) row/document count for pagination fan-out. */
    long countRows(DbTable table);
	TableDataPage getTableDataPage(TableDataPage.Request request);
	int writePageData(DbTable table, DataPage dataPage);
	boolean clearTables(String... tableNames);

	/**
	 * Empty tables quickly for restore prep. Default falls back to {@link #clearTables}.
	 * Implementations should {@code TRUNCATE} and fall back to {@code DELETE} if truncate is blocked.
	 */
	default boolean truncateTables(String... tableNames) {
		return clearTables(tableNames);
	}

	void updateSequence(String qualifiedSequenceName, Long newValue);
	Long getMaxColumnValue(String tableName, String columnName);

	/** Filtered count for subset drivers. Default: unsupported. */
	default long countRows(DbTable table, String whereFragment) {
		throw unsupportedSubset();
	}

	/** Seed primary keys for a subset driver. Default: unsupported. */
	default Set<String> selectPrimaryKeys(DbTable table, String whereFragment, Integer limit) {
		throw unsupportedSubset();
	}

	/** Parent PK keys referenced by selected child rows via an FK. Default: unsupported. */
	default Set<String> selectParentPrimaryKeys(DbTable child, DbTable parent,
			List<String> childFkColumns, List<String> parentRefColumns, Collection<String> childPkKeys) {
		throw unsupportedSubset();
	}

	/** Sample rows for subset builder browse grid. {@code page} is 0-based. Default: unsupported. */
	default SubsetBrowseResult browseRows(DbTable table, String whereFragment, Integer pageSize, Integer page) {
		throw unsupportedSubset();
	}

	/** Whether this DML service supports subset backups. */
	default boolean supportsSubset() {
		return false;
	}

	private static RuntimeException unsupportedSubset() {
		return Exceptions.badRequest("subset-not-supported-for-datasource").get();
	}
}
