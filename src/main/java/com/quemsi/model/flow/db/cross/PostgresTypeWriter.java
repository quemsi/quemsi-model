package com.quemsi.model.flow.db.cross;

import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.sql.DbColumn;

public final class PostgresTypeWriter implements TargetTypeWriter {
	@Override
	public DatasourceType target() {
		return DatasourceType.POSTGRES;
	}

	@Override
	public String btreeIndexType() {
		return "btree";
	}

	@Override
	public int maxNumericPrecision() {
		return Integer.MAX_VALUE;
	}

	@Override
	public int maxCharLength() {
		return Integer.MAX_VALUE;
	}

	@Override
	public void apply(DbColumn column, PortableColumn portable, CrossDbReport report) {
		PortableType type = portable.getType();
		String udt = switch (type) {
			case BOOLEAN -> "bool";
			case SMALLINT -> "int2";
			case INTEGER -> "int4";
			case BIGINT -> "int8";
			case NUMERIC -> "numeric";
			case FLOAT -> "float8";
			case REAL -> "float4";
			case CHAR -> "bpchar";
			case VARCHAR -> "varchar";
			case TEXT -> "text";
			case BINARY -> "bytea";
			case UUID -> "uuid";
			case DATE -> "date";
			case TIME -> "time";
			case TIMESTAMP -> "timestamp";
			case TIMESTAMPTZ -> "timestamptz";
		};
		column.setDataType(udt);
		column.setColumnType(udt);
		column.setMaxLength(type == PortableType.CHAR || type == PortableType.VARCHAR ? portable.getLength() : null);
		if (type == PortableType.NUMERIC) {
			column.setNumPrecision(portable.getPrecision());
			column.setNumScale(portable.getScale() != null ? portable.getScale() : 0);
		} else {
			column.setNumPrecision(null);
			column.setNumScale(null);
		}
		column.setIdentity(portable.isIdentity());
		column.setNullable(portable.isNullable());
		column.setXmlSchemaCollection(null);
		column.setColumnDefault(portable.isIdentity() ? null : DefaultLiterals.format(portable, target()));
		column.setPortable(portable);
		column.setOmitted(false);
	}
}
