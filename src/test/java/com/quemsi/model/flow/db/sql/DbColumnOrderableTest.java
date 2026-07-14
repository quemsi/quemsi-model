package com.quemsi.model.flow.db.sql;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;

public class DbColumnOrderableTest {

	@Test
	public void givenBlobColumn_whenIsOrderable_thenFalse() {
		assertThat(column("TOKEN_INFO", "BLOB").isOrderable(), equalTo(false));
		assertThat(column("NOTES", "CLOB").isOrderable(), equalTo(false));
		assertThat(column("DATA", "LONG RAW").isOrderable(), equalTo(false));
	}

	@Test
	public void givenScalarColumn_whenIsOrderable_thenTrue() {
		assertThat(column("TOKEN_TEXT", "VARCHAR2").isOrderable(), equalTo(true));
		assertThat(column("TOKEN_COUNT", "NUMBER").isOrderable(), equalTo(true));
		assertThat(column("RAW_BYTES", "RAW").isOrderable(), equalTo(true));
	}

	@Test
	public void givenTableWithBlob_whenOrderableColumnNames_thenExcludesBlob() {
		DbTable table = new DbTable("SH", "DR$UP_TEXT_IDX$I");
		table.addColumn(column("TOKEN_TEXT", "VARCHAR2", 1));
		table.addColumn(column("TOKEN_TYPE", "NUMBER", 2));
		table.addColumn(column("TOKEN_INFO", "BLOB", 3));
		table.addColumn(column("TOKEN_COUNT", "NUMBER", 4));

		assertThat(table.orderableColumnNames(), contains("TOKEN_TEXT", "TOKEN_TYPE", "TOKEN_COUNT"));
		assertThat(table.orderableColumnNames(), not(contains("TOKEN_INFO")));
	}

	private DbColumn column(String name, String dataType) {
		return column(name, dataType, 1);
	}

	private DbColumn column(String name, String dataType, int ordinal) {
		return DbColumn.builder()
			.name(name)
			.dataType(dataType)
			.columnType(dataType)
			.ordinalPosition(ordinal)
			.build();
	}
}
