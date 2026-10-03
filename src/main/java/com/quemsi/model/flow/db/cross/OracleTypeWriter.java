package com.quemsi.model.flow.db.cross;

import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.sql.DbColumn;

public final class OracleTypeWriter implements TargetTypeWriter {
	static final int VARCHAR_LIMIT = 4000;

	@Override
	public DatasourceType target() {
		return DatasourceType.ORACLE;
	}

	@Override
	public String btreeIndexType() {
		return "NORMAL";
	}

	@Override
	public int maxNumericPrecision() {
		return 38;
	}

	@Override
	public int maxCharLength() {
		return VARCHAR_LIMIT;
	}

	@Override
	public void apply(DbColumn column, PortableColumn portable, CrossDbReport report) {
		String dataType;
		Integer length = null;
		Integer precision = null;
		Integer scale = null;
		switch (portable.getType()) {
			case BOOLEAN -> {
				dataType = "number";
				precision = 1;
				scale = 0;
			}
			case SMALLINT -> {
				dataType = "number";
				precision = 5;
				scale = 0;
			}
			case INTEGER -> {
				dataType = "number";
				precision = 10;
				scale = 0;
			}
			case BIGINT -> {
				dataType = "number";
				precision = 19;
				scale = 0;
			}
			case NUMERIC -> {
				dataType = "number";
				precision = portable.getPrecision() != null ? portable.getPrecision() : 38;
				scale = portable.getScale() != null ? portable.getScale() : 0;
			}
			case FLOAT -> dataType = "binary_double";
			case REAL -> dataType = "binary_float";
			case CHAR -> {
				if (portable.getLength() == null || portable.getLength() > VARCHAR_LIMIT) {
					dataType = "clob";
					report.warnOnce("oracle-char-max", "character columns longer than varchar2 were written as clob");
				} else {
					dataType = "char";
					length = portable.getLength();
				}
			}
			case VARCHAR -> {
				if (portable.getLength() == null || portable.getLength() > VARCHAR_LIMIT) {
					dataType = "clob";
					report.warnOnce("oracle-char-max", "character columns longer than varchar2 were written as clob");
				} else {
					dataType = "varchar2";
					length = portable.getLength();
				}
			}
			case TEXT -> dataType = "clob";
			case BINARY -> dataType = "blob";
			case UUID -> {
				dataType = "varchar2";
				length = 36;
				report.warnOnce("uuid-as-varchar", "uuid columns were written as varchar because the target has no uuid type");
			}
			case DATE -> dataType = "date";
			case TIME -> dataType = "timestamp";
			case TIMESTAMP -> dataType = "timestamp";
			case TIMESTAMPTZ -> dataType = "timestamp with time zone";
			default -> dataType = "varchar2";
		}
		column.setDataType(dataType);
		column.setColumnType(dataType);
		column.setMaxLength(length);
		column.setNumPrecision(precision);
		column.setNumScale(scale);
		column.setIdentity(portable.isIdentity());
		column.setNullable(portable.isNullable());
		column.setXmlSchemaCollection(null);
		column.setColumnDefault(portable.isIdentity() ? null : DefaultLiterals.format(portable, target()));
		column.setPortable(portable);
		column.setOmitted(false);
	}
}
