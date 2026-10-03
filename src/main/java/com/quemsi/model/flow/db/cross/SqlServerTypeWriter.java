package com.quemsi.model.flow.db.cross;

import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.sql.DbColumn;

public final class SqlServerTypeWriter implements TargetTypeWriter {
	/** nvarchar character limit. Longer values are written as nvarchar(max). */
	static final int NVARCHAR_CHAR_LIMIT = 4000;
	static final int VARBINARY_LIMIT = 8000;

	@Override
	public DatasourceType target() {
		return DatasourceType.SQLSERVER;
	}

	@Override
	public String btreeIndexType() {
		return "NONCLUSTERED";
	}

	@Override
	public int maxNumericPrecision() {
		return 38;
	}

	@Override
	public int maxCharLength() {
		return NVARCHAR_CHAR_LIMIT;
	}

	@Override
	public void apply(DbColumn column, PortableColumn portable, CrossDbReport report) {
		PortableType type = portable.getType();
		String dataType;
		Integer maxLength = null;
		Integer precision = null;
		Integer scale = null;
		switch (type) {
			case BOOLEAN -> dataType = "bit";
			case SMALLINT -> dataType = "smallint";
			case INTEGER -> dataType = "int";
			case BIGINT -> dataType = "bigint";
			case NUMERIC -> {
				dataType = "decimal";
				precision = portable.getPrecision() != null ? portable.getPrecision() : 38;
				scale = portable.getScale() != null ? portable.getScale() : 0;
			}
			case FLOAT -> dataType = "float";
			case REAL -> dataType = "real";
			case CHAR -> {
				if (portable.getLength() == null || portable.getLength() > NVARCHAR_CHAR_LIMIT) {
					dataType = "nvarchar";
					maxLength = -1;
					report.warnOnce("sqlserver-char-max", "character columns longer than nvarchar were written as nvarchar(max)");
				} else {
					dataType = "nchar";
					maxLength = portable.getLength() * 2;
				}
			}
			case VARCHAR -> {
				if (portable.getLength() == null || portable.getLength() > NVARCHAR_CHAR_LIMIT) {
					dataType = "nvarchar";
					maxLength = -1;
					report.warnOnce("sqlserver-char-max", "character columns longer than nvarchar were written as nvarchar(max)");
				} else {
					dataType = "nvarchar";
					maxLength = portable.getLength() * 2;
				}
			}
			case TEXT -> {
				dataType = "nvarchar";
				maxLength = -1;
			}
			case BINARY -> {
				dataType = "varbinary";
				if (portable.getLength() == null || portable.getLength() > VARBINARY_LIMIT) {
					maxLength = -1;
				} else {
					maxLength = portable.getLength();
				}
			}
			case UUID -> dataType = "uniqueidentifier";
			case DATE -> dataType = "date";
			case TIME -> dataType = "time";
			case TIMESTAMP -> dataType = "datetime2";
			case TIMESTAMPTZ -> dataType = "datetimeoffset";
			default -> dataType = "nvarchar";
		}
		column.setDataType(dataType);
		column.setColumnType(dataType);
		column.setMaxLength(maxLength);
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
