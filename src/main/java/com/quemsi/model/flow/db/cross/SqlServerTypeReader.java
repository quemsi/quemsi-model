package com.quemsi.model.flow.db.cross;

import java.util.Set;

import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;

public final class SqlServerTypeReader implements SourceTypeReader {
	private static final Set<String> OMITTED = Set.of(
		"geography", "geometry", "hierarchyid", "sql_variant"
	);

	@Override
	public DatasourceType source() {
		return DatasourceType.SQLSERVER;
	}

	@Override
	public ColumnMapping read(DbColumn column, DbModel model, CrossDbReport report) {
		String type = TypeNames.base(column.getDataType() != null ? column.getDataType() : column.getColumnType());
		if (OMITTED.contains(type)) {
			return ColumnMapping.omit(type);
		}
		if ("xml".equals(type) && column.getXmlSchemaCollection() != null && !column.getXmlSchemaCollection().isBlank()) {
			return ColumnMapping.omit("typed xml");
		}
		PortableType portable = map(type, column);
		if (portable == null) {
			return ColumnMapping.omit(type.isEmpty() ? "unknown type" : type);
		}
		Integer length = stringLength(portable, column);
		Integer precision = precision(portable, type, column);
		Integer scale = scale(portable, type, column);
		return ColumnMapping.mapped(PortableColumns.of(column, portable, length, precision, scale));
	}

	private static PortableType map(String type, DbColumn column) {
		return switch (type) {
			case "bit" -> PortableType.BOOLEAN;
			case "tinyint", "smallint" -> PortableType.SMALLINT;
			case "int", "integer" -> PortableType.INTEGER;
			case "bigint" -> PortableType.BIGINT;
			case "decimal", "numeric", "money", "smallmoney" -> PortableType.NUMERIC;
			case "float" -> column.getNumPrecision() != null && column.getNumPrecision() <= 24
				? PortableType.REAL : PortableType.FLOAT;
			case "real" -> PortableType.REAL;
			case "char", "nchar" -> TypeNames.maxLength(column.getMaxLength(), column.getColumnType())
				? PortableType.TEXT : PortableType.CHAR;
			case "varchar", "nvarchar", "sysname" -> TypeNames.maxLength(column.getMaxLength(), column.getColumnType())
				? PortableType.TEXT : PortableType.VARCHAR;
			case "text", "ntext", "xml" -> PortableType.TEXT;
			case "binary", "varbinary", "image", "rowversion", "timestamp" -> PortableType.BINARY;
			case "uniqueidentifier" -> PortableType.UUID;
			case "date" -> PortableType.DATE;
			case "time" -> PortableType.TIME;
			case "datetime", "datetime2", "smalldatetime" -> PortableType.TIMESTAMP;
			case "datetimeoffset" -> PortableType.TIMESTAMPTZ;
			default -> null;
		};
	}

	private static Integer stringLength(PortableType portable, DbColumn column) {
		if (portable != PortableType.CHAR && portable != PortableType.VARCHAR) {
			return null;
		}
		if ("sysname".equals(TypeNames.base(column.getDataType()))) {
			return 128;
		}
		Integer length = column.getMaxLength();
		if (length == null || length < 0) {
			return null;
		}
		return length;
	}

	private static Integer precision(PortableType portable, String type, DbColumn column) {
		if (portable != PortableType.NUMERIC) {
			return null;
		}
		if ("money".equals(type)) {
			return 19;
		}
		if ("smallmoney".equals(type)) {
			return 10;
		}
		return column.getNumPrecision();
	}

	private static Integer scale(PortableType portable, String type, DbColumn column) {
		if (portable != PortableType.NUMERIC) {
			return null;
		}
		if ("money".equals(type) || "smallmoney".equals(type)) {
			return 4;
		}
		return column.getNumScale();
	}

}
