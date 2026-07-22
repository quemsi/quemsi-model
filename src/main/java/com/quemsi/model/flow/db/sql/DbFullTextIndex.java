package com.quemsi.model.flow.db.sql;

import java.util.LinkedList;
import java.util.List;

import com.quemsi.model.util.CommonHelpers;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbFullTextIndex {
	private String schemaName;
	private String tableName;
	/** Unique index used by KEY INDEX clause (usually the PK). */
	private String uniqueIndexName;
	private String catalogName;
	/** AUTO, MANUAL, or OFF. */
	private String changeTracking;
	/** SYSTEM, a user stoplist name, or null/OFF. */
	private String stoplistName;
	@Builder.Default
	private List<Column> columns = new LinkedList<>();

	public String qualifiedTableName() {
		return CommonHelpers.qualifiedName(schemaName, tableName);
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@Builder
	public static class Column {
		private String columnName;
		/** Optional TYPE COLUMN for varbinary/image document columns. */
		private String typeColumnName;
		private Integer languageId;
	}
}
