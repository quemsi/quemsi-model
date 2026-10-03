package com.quemsi.model.flow.db.cross;

import java.util.Set;

import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;

public final class MysqlTypeReader implements SourceTypeReader {
	private static final Set<String> SPATIAL = Set.of(
		"geometry", "point", "linestring", "polygon", "multipoint", "multilinestring",
		"multipolygon", "geometrycollection", "geomcollection"
	);

	@Override
	public DatasourceType source() {
		return DatasourceType.MYSQL;
	}

	@Override
	public ColumnMapping read(DbColumn column, DbModel model, CrossDbReport report) {
		String type = TypeNames.base(column.getDataType() != null ? column.getDataType() : column.getColumnType());
		String full = column.getColumnType() == null ? type : column.getColumnType().toLowerCase();
		if ("set".equals(type) || SPATIAL.contains(type)) {
			return ColumnMapping.omit(type);
		}
		boolean unsigned = TypeNames.unsigned(full);
		if ("tinyint".equals(type) && (full.contains("(1)") || "boolean".equals(type) || "bool".equals(type))) {
			return ColumnMapping.mapped(PortableColumns.of(column, PortableType.BOOLEAN, null, null, null));
		}
		if ("bool".equals(type) || "boolean".equals(type)) {
			return ColumnMapping.mapped(PortableColumns.of(column, PortableType.BOOLEAN, null, null, null));
		}
		if ("bigint".equals(type) && unsigned) {
			return ColumnMapping.omit("bigint unsigned");
		}
		if ("enum".equals(type)) {
			report.warnOnce("mysql-enum", "MySQL enum constraints were dropped and stored as varchar");
			Integer length = column.getMaxLength() != null && column.getMaxLength() > 0 ? column.getMaxLength() : 255;
			return ColumnMapping.mapped(PortableColumns.of(column, PortableType.VARCHAR, length, null, null));
		}
		if ("year".equals(type)) {
			return ColumnMapping.mapped(PortableColumns.of(column, PortableType.SMALLINT, null, null, null));
		}
		if ("timestamp".equals(type)) {
			report.warnOnce("mysql-timestamp",
				"MySQL TIMESTAMP values were restored from the backup as stored, without session time zone conversion");
			return ColumnMapping.mapped(PortableColumns.of(column, PortableType.TIMESTAMPTZ, null, null, null));
		}
		if ("json".equals(type)) {
			report.warnOnce("mysql-json", "json columns were stored as text");
			return ColumnMapping.mapped(PortableColumns.of(column, PortableType.TEXT, null, null, null));
		}
		PortableType portable = map(type, unsigned, column);
		if (portable == null) {
			return ColumnMapping.omit(type.isEmpty() ? "unknown type" : type);
		}
		Integer length = (portable == PortableType.CHAR || portable == PortableType.VARCHAR)
			? positive(column.getMaxLength()) : null;
		Integer precision = portable == PortableType.NUMERIC ? column.getNumPrecision() : null;
		Integer scale = portable == PortableType.NUMERIC ? column.getNumScale() : null;
		return ColumnMapping.mapped(PortableColumns.of(column, portable, length, precision, scale));
	}

	private static PortableType map(String type, boolean unsigned, DbColumn column) {
		return switch (type) {
			case "tinyint" -> PortableType.SMALLINT;
			case "smallint" -> unsigned ? PortableType.INTEGER : PortableType.SMALLINT;
			case "mediumint", "int", "integer" -> unsigned ? PortableType.BIGINT : PortableType.INTEGER;
			case "bigint" -> PortableType.BIGINT;
			case "decimal", "numeric" -> PortableType.NUMERIC;
			case "float" -> PortableType.REAL;
			case "double", "double precision" -> PortableType.FLOAT;
			case "char" -> TypeNames.maxLength(column.getMaxLength(), column.getColumnType())
				? PortableType.TEXT : PortableType.CHAR;
			case "varchar" -> TypeNames.maxLength(column.getMaxLength(), column.getColumnType())
				? PortableType.TEXT : PortableType.VARCHAR;
			case "tinytext", "text", "mediumtext", "longtext" -> PortableType.TEXT;
			case "binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob" -> PortableType.BINARY;
			case "date" -> PortableType.DATE;
			case "time" -> PortableType.TIME;
			case "datetime" -> PortableType.TIMESTAMP;
			default -> null;
		};
	}

	private static Integer positive(Integer length) {
		if (length == null || length < 0) {
			return null;
		}
		return length;
	}
}
