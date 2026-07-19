package com.quemsi.model.flow.db.mysql;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.sql.Date;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.quemsi.model.flow.db.sql.DbColumn;

public class DMLServiceMysqlYearTest {

	@Test
	public void toMysqlYearValue_supportsDateStringNumberAndEpoch() {
		assertThat(DMLServiceMysql.toMysqlYearValue(null), nullValue());
		assertThat(DMLServiceMysql.toMysqlYearValue((short) 2006), equalTo(2006));
		assertThat(DMLServiceMysql.toMysqlYearValue(2006), equalTo(2006));
		assertThat(DMLServiceMysql.toMysqlYearValue(Date.valueOf("2006-01-01")), equalTo(2006));
		assertThat(DMLServiceMysql.toMysqlYearValue(LocalDate.of(2007, 6, 1)), equalTo(2007));
		assertThat(DMLServiceMysql.toMysqlYearValue("2008"), equalTo(2008));
		assertThat(DMLServiceMysql.toMysqlYearValue("2009-01-01"), equalTo(2009));
		assertThat(DMLServiceMysql.toMysqlYearValue(Date.valueOf("2010-01-01").getTime()), equalTo(2010));
	}

	@Test
	public void isYearType_matchesYearColumnType() {
		assertThat(DMLServiceMysql.isYearType(DbColumn.builder().name("release_year").columnType("year").build()), is(true));
		assertThat(DMLServiceMysql.isYearType(DbColumn.builder().name("release_year").columnType("year(4)").build()), is(true));
		assertThat(DMLServiceMysql.isYearType(DbColumn.builder().name("title").columnType("varchar(255)").build()), is(false));
	}
}
