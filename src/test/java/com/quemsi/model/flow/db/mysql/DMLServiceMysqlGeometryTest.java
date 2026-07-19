package com.quemsi.model.flow.db.mysql;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.in.CustomSerializedColumn;

public class DMLServiceMysqlGeometryTest {

	@Test
	public void isGeometryType_matchesSpatialTypes() {
		assertThat(DMLServiceMysql.isGeometryType(col("geometry")), is(true));
		assertThat(DMLServiceMysql.isGeometryType(col("point")), is(true));
		assertThat(DMLServiceMysql.isGeometryType(col("varchar(50)")), is(false));
	}

	@Test
	public void stripMysqlSridPrefix_removesLeadingSrid() {
		/* SRID 0 little-endian + little-endian WKB point header */
		byte[] mysqlGeom = new byte[] {
			0, 0, 0, 0, /* SRID */
			1, /* LE */
			1, 0, 0, 0, /* Point */
			0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0
		};
		byte[] wkb = DMLServiceMysql.stripMysqlSridPrefix(mysqlGeom);
		assertThat(wkb[0], equalTo((byte) 1));
		assertThat(wkb.length, equalTo(mysqlGeom.length - 4));
	}

	@Test
	public void toWkbBytes_acceptsBase64BinaryColumnAndMap() {
		byte[] mysqlGeom = new byte[] {
			0, 0, 0, 0,
			1, 1, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0
		};
		byte[] expected = DMLServiceMysql.stripMysqlSridPrefix(mysqlGeom);

		assertThat(DMLServiceMysql.toWkbBytes(mysqlGeom), equalTo(expected));
		assertThat(DMLServiceMysql.toWkbBytes(Base64.getEncoder().encodeToString(mysqlGeom)), equalTo(expected));
		assertThat(DMLServiceMysql.toWkbBytes(CustomSerializedColumn.BinaryColumn.builder()
			.dbType("geometry").dataId("location").data(mysqlGeom).build()), equalTo(expected));
		assertThat(DMLServiceMysql.toWkbBytes(Map.of(
			"dbType", "geometry",
			"dataId", "location",
			"data", Base64.getEncoder().encodeToString(mysqlGeom)
		)), equalTo(expected));
	}

	private static DbColumn col(String columnType) {
		return DbColumn.builder().name("location").dataType(columnType).columnType(columnType).build();
	}
}
