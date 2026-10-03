package com.quemsi.model.flow.db.cross;

/**
 * Result of reading one source column. {@code column} is null when the column is omitted.
 */
public record ColumnMapping(PortableColumn column, String warning, boolean omit, String omitReason) {
	public static ColumnMapping mapped(PortableColumn column) {
		return new ColumnMapping(column, null, false, null);
	}

	public static ColumnMapping mapped(PortableColumn column, String warning) {
		return new ColumnMapping(column, warning, false, null);
	}

	public static ColumnMapping omit(String reason) {
		return new ColumnMapping(null, null, true, reason);
	}
}
