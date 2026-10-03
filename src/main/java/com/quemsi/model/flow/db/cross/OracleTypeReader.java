package com.quemsi.model.flow.db.cross;

import java.util.Set;

import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;

public final class OracleTypeReader implements SourceTypeReader {
	private static final Set<String> OMITTED = Set.of(
		"long", "long raw", "bfile", "xmltype", "sdo_geometry", "urowid", "rowid"
	);

	@Override
	public DatasourceType source() {
		return DatasourceType.ORACLE;
	}

	@Override
	public ColumnMapping read(DbColumn column, DbModel model, CrossDbReport report) {
		String type = TypeNames.base(column.getDataType() != null ? column.getDataType() : column.getColumnType());
		if (OMITTED.contains(type)) {
			return ColumnMapping.omit(type);
		}
		if ("number".equals(type) && Integer.valueOf(1).equals(column.getNumPrecision())
			&& (column.getNumScale() == null || column.getNumScale() == 0)) {
			return ColumnMapping.mapped(PortableColumns.of(column, PortableType.BOOLEAN, null, null, null));
		}
		PortableType portable = switch (type) {
			case "number", "numeric", "decimal", "float" -> "float".equals(type) ? PortableType.FLOAT : PortableType.NUMERIC;
			case "binary_float" -> PortableType.REAL;
			case "binary_double" -> PortableType.FLOAT;
			case "char", "nchar" -> PortableType.CHAR;
			case "varchar2", "nvarchar2" -> PortableType.VARCHAR;
			case "clob", "nclob" -> PortableType.TEXT;
			case "raw", "blob" -> PortableType.BINARY;
			case "date" -> PortableType.TIMESTAMP;
			case "timestamp" -> PortableType.TIMESTAMP;
			default -> null;
		};
		if (type.startsWith("timestamp")) {
			portable = type.contains("time zone") ? PortableType.TIMESTAMPTZ : PortableType.TIMESTAMP;
		}
		if (portable == null) {
			return ColumnMapping.omit(type.isEmpty() ? "unknown type" : type);
		}
		Integer precision = null;
		Integer scale = null;
		if (portable == PortableType.NUMERIC) {
			precision = column.getNumPrecision() != null ? column.getNumPrecision() : 38;
			scale = column.getNumScale() != null ? column.getNumScale() : 0;
		}
		Integer length = (portable == PortableType.CHAR || portable == PortableType.VARCHAR)
			? positive(column.getMaxLength()) : null;
		return ColumnMapping.mapped(PortableColumns.of(column, portable, length, precision, scale));
	}

	private static Integer positive(Integer length) {
		if (length == null || length < 0) {
			return null;
		}
		return length;
	}
}
