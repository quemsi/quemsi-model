package com.quemsi.model.flow.db.cross;

import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.sql.DbColumn;

public final class MysqlTypeWriter implements TargetTypeWriter {
	static final int VARCHAR_LIMIT = 16383;

	@Override
	public DatasourceType target() {
		return DatasourceType.MYSQL;
	}

	@Override
	public String btreeIndexType() {
		return "BTREE";
	}

	@Override
	public int maxNumericPrecision() {
		return 65;
	}

	@Override
	public int maxCharLength() {
		return VARCHAR_LIMIT;
	}

	@Override
	public void apply(DbColumn column, PortableColumn portable, CrossDbReport report) {
		String columnType = switch (portable.getType()) {
			case BOOLEAN -> "tinyint(1)";
			case SMALLINT -> "smallint";
			case INTEGER -> "int";
			case BIGINT -> "bigint";
			case NUMERIC -> decimal(portable);
			case FLOAT -> "double";
			case REAL -> "float";
			case CHAR -> chars("char", portable, report);
			case VARCHAR -> chars("varchar", portable, report);
			case TEXT -> "longtext";
			case BINARY -> binary(portable);
			case UUID -> {
				report.warnOnce("uuid-as-varchar", "uuid columns were written as varchar because the target has no uuid type");
				yield "varchar(36)";
			}
			case DATE -> "date";
			case TIME -> "time";
			case TIMESTAMP -> "datetime";
			case TIMESTAMPTZ -> "timestamp";
		};
		column.setColumnType(columnType);
		column.setDataType(TypeNames.base(columnType));
		column.setMaxLength(portable.getLength());
		column.setNumPrecision(portable.getType() == PortableType.NUMERIC ? portable.getPrecision() : null);
		column.setNumScale(portable.getType() == PortableType.NUMERIC ? portable.getScale() : null);
		column.setIdentity(portable.isIdentity());
		column.setNullable(portable.isNullable());
		column.setXmlSchemaCollection(null);
		column.setColumnDefault(portable.isIdentity() ? null : DefaultLiterals.format(portable, target()));
		column.setPortable(portable);
		column.setOmitted(false);
	}

	private static String decimal(PortableColumn portable) {
		int precision = portable.getPrecision() != null ? portable.getPrecision() : 38;
		int scale = portable.getScale() != null ? portable.getScale() : 0;
		if (precision > 65) {
			precision = 65;
		}
		if (scale > precision) {
			scale = precision;
		}
		return "decimal(" + precision + "," + scale + ")";
	}

	private static String chars(String base, PortableColumn portable, CrossDbReport report) {
		if (portable.getLength() == null || portable.getLength() > VARCHAR_LIMIT) {
			report.warnOnce("mysql-char-max", "character columns longer than varchar were written as longtext");
			return "longtext";
		}
		return base + "(" + portable.getLength() + ")";
	}

	private static String binary(PortableColumn portable) {
		if (portable.getLength() == null || portable.getLength() > 65535) {
			return "longblob";
		}
		return "varbinary(" + portable.getLength() + ")";
	}
}
