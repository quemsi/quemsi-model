package com.quemsi.model.flow.db.cross;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbEnumType;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.DbView;
import com.quemsi.model.flow.in.TableData.DataPage;

public class CrossDbTranslatorTest {

	@Test
	public void sqlServerTypesMapOntoPostgres() {
		DbModel model = sqlServerModel();
		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.POSTGRES);
		DbTable table = model.findTable("dbo.Widget").orElseThrow();
		DbColumn[] columns = table.orderedColumns();

		assertThat(columns.length, equalTo(4));
		assertThat(columns[0].getColumnType(), equalTo("int4"));
		assertThat(columns[0].isIdentity(), is(true));
		assertThat(columns[1].getColumnType(), equalTo("varchar"));
		assertThat(columns[1].getMaxLength(), equalTo(50));
		assertThat(columns[2].getColumnType(), equalTo("bool"));
		assertThat(columns[2].getColumnDefault(), equalTo("false"));
		assertThat(columns[3].getColumnType(), equalTo("uuid"));
		assertThat(table.findColumn("geo").isPresent(), is(false));
		assertThat(plan.sourceIndexes("dbo.Widget"), equalTo(new int[] { 0, 1, 2, 4 }));
		assertThat(joined(plan), containsString("omitted column dbo.Widget.geo: geography"));
		assertThat(joined(plan), containsString("omitted default on dbo.Widget.name: expression"));
		assertThat(joined(plan), containsString("omitted view dbo.WidgetView"));
		assertThat(model.getViews().size(), equalTo(0));
	}

	@Test
	public void omittedKeyColumnDropsForeignKeyAndNonBtreeIndex() {
		DbModel model = sqlServerModel();
		DbTable parent = model.addTable("Parent", "dbo");
		parent.addColumn(column("id", "int", 1));
		parent.getPkColumnNames().add("id");
		model.getReferenceInfos().add(new ReferenceInfo(
			"fk_geo", "dbo", "Widget", Set.of("geo"), "dbo", "Parent", Set.of("id")));
		IndexInfo gin = new IndexInfo("dbo", "Widget", "ix_geo", false, "SPATIAL");
		gin.getColumns().add("geo");
		model.getIndexes().computeIfAbsent("dbo.Widget", key -> new LinkedHashMap<>()).put(gin.getIndexName(), gin);
		IndexInfo clustered = new IndexInfo("dbo", "Widget", "ix_name", false, "CLUSTERED");
		clustered.getColumns().add("name");
		model.getIndexes().get("dbo.Widget").put(clustered.getIndexName(), clustered);

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.POSTGRES);

		assertThat(model.getReferenceInfos().size(), equalTo(0));
		assertThat(joined(plan), containsString("dropped foreign key fk_geo"));
		assertThat(joined(plan), containsString("dropped index ix_geo"));
		IndexInfo kept = model.indexesForTable("dbo.Widget").get("ix_name");
		assertThat(kept.getIndexType(), equalTo("btree"));
	}

	@Test
	public void numericPrecisionAboveTargetIsOmitted() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.POSTGRES.name());
		DbTable table = model.addTable("wide", "public");
		table.addColumn(DbColumn.builder().name("amount").dataType("numeric").columnType("numeric")
			.ordinalPosition(1).numPrecision(40).numScale(2).nullable(true).build());

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.SQLSERVER);

		assertThat(model.findTable("public.wide").isPresent(), is(false));
		assertThat(joined(plan), containsString("numeric precision 40 exceeds 38"));
	}

	@Test
	public void mysqlUnsignedAndEnum() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.MYSQL.name());
		DbTable table = model.addTable("item", null);
		table.addColumn(DbColumn.builder().name("flag").dataType("tinyint").columnType("tinyint(1)")
			.ordinalPosition(1).nullable(false).build());
		table.addColumn(DbColumn.builder().name("qty").dataType("int").columnType("int unsigned")
			.ordinalPosition(2).nullable(true).build());
		table.addColumn(DbColumn.builder().name("kind").dataType("enum").columnType("enum('a','bb')")
			.ordinalPosition(3).maxLength(2).nullable(true).build());
		table.addColumn(DbColumn.builder().name("huge").dataType("bigint").columnType("bigint unsigned")
			.ordinalPosition(4).nullable(true).build());

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.POSTGRES);
		DbTable translated = model.findTable("public.item").orElseThrow();

		assertThat(translated.column("flag").getColumnType(), equalTo("bool"));
		assertThat(translated.column("qty").getColumnType(), equalTo("int8"));
		assertThat(translated.column("kind").getColumnType(), equalTo("varchar"));
		assertThat(translated.findColumn("huge").isPresent(), is(false));
		assertThat(joined(plan), containsString("enum constraints were dropped"));
		assertThat(plan.archiveName("public.item"), equalTo("item"));
	}

	@Test
	public void oracleDateIsTimestampAndBooleanNumberRoundTrips() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.ORACLE.name());
		model.setSchemas(Set.of("HR"));
		DbTable table = model.addTable("EMP", "HR");
		table.addColumn(DbColumn.builder().name("HIRED").dataType("DATE").columnType("DATE")
			.ordinalPosition(1).nullable(true).build());
		table.addColumn(DbColumn.builder().name("ACTIVE").dataType("NUMBER").columnType("NUMBER")
			.ordinalPosition(2).numPrecision(1).numScale(0).nullable(false).build());

		CrossDbTranslator.translate(model, DatasourceType.POSTGRES);
		DbTable translated = model.findTable("HR.EMP").orElseThrow();
		assertThat(translated.column("HIRED").getColumnType(), equalTo("timestamp"));
		assertThat(translated.column("ACTIVE").getColumnType(), equalTo("bool"));
	}

	@Test
	public void postgresJsonAndArray() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.POSTGRES.name());
		DbTable table = model.addTable("doc", "public");
		table.addColumn(DbColumn.builder().name("body").dataType("jsonb").columnType("jsonb")
			.ordinalPosition(1).nullable(true).build());
		table.addColumn(DbColumn.builder().name("tags").dataType("_text").columnType("_text")
			.ordinalPosition(2).nullable(true).build());
		model.getEnumTypes().add(DbEnumType.builder().schema("public").name("color").labels(List.of("red", "blue")).build());
		DbTable colored = model.addTable("paint", "public");
		colored.addColumn(DbColumn.builder().name("color").dataType("color").columnType("color")
			.ordinalPosition(1).nullable(true).build());

		CrossDbPlan plan = CrossDbTranslator.translate(model, DatasourceType.MYSQL);
		assertThat(model.findTable("doc").orElseThrow().column("body").getColumnType(), equalTo("longtext"));
		assertThat(model.findTable("doc").orElseThrow().findColumn("tags").isPresent(), is(false));
		assertThat(model.findTable("paint").orElseThrow().column("color").getColumnType(), equalTo("varchar(4)"));
		assertThat(joined(plan), containsString("json and jsonb"));
		assertThat(model.getEnumTypes().size(), equalTo(0));
	}

	@Test
	public void mysqlTargetCollidingSchemasFail() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.SQLSERVER.name());
		model.addTable("Item", "dbo").addColumn(column("id", "int", 1));
		model.addTable("Item", "sales").addColumn(column("id", "int", 1));

		BaseRuntimeException ex = assertThrows(BaseRuntimeException.class,
			() -> CrossDbTranslator.translate(model, DatasourceType.MYSQL));
		assertThat(ex.getMessage(), containsString("cross-db-table-name-collision"));
	}

	@Test
	public void readersAndWritersCoverPortableFamilies() {
		CrossDbReport report = new CrossDbReport();
		DbColumn bit = column("active", "bit", 1);
		ColumnMapping bitMapping = new SqlServerTypeReader().read(bit, new DbModel(), report);
		assertThat(bitMapping.column().getType(), is(PortableType.BOOLEAN));

		DbColumn money = column("price", "money", 1);
		assertThat(new SqlServerTypeReader().read(money, new DbModel(), report).column().getPrecision(), equalTo(19));

		DbColumn oracleDate = DbColumn.builder().name("hired").dataType("DATE").ordinalPosition(1).build();
		assertThat(new OracleTypeReader().read(oracleDate, new DbModel(), report).column().getType(), is(PortableType.TIMESTAMP));

		new PostgresTypeWriter().apply(bit, bitMapping.column(), report);
		assertThat(bit.getColumnType(), equalTo("bool"));

		DbColumn uuid = DbColumn.builder().name("id").ordinalPosition(1).build();
		new OracleTypeWriter().apply(uuid, PortableColumn.builder().type(PortableType.UUID).nullable(true).build(), report);
		assertThat(uuid.getDataType(), equalTo("varchar2"));
		assertThat(uuid.getMaxLength(), equalTo(36));
		assertThat(joinedReport(report), containsString("no uuid type"));

		DbColumn text = DbColumn.builder().name("note").ordinalPosition(1).build();
		new SqlServerTypeWriter().apply(text, PortableColumn.builder().type(PortableType.VARCHAR).length(5000).nullable(true).build(), report);
		assertThat(text.getDataType(), equalTo("nvarchar"));
		assertThat(text.getMaxLength(), equalTo(-1));
	}

	@Test
	public void literalDefaultsKeepNumbersAndQuotedStrings() {
		assertThat(LiteralDefaults.parse("((0))").orElseThrow().value(), equalTo("0"));
		assertThat(LiteralDefaults.parse("(N'it''s')").orElseThrow().value(), equalTo("it's"));
		assertThat(LiteralDefaults.parse("(getdate())").isPresent(), is(false));
		assertThat(LiteralDefaults.parse("nextval('public.s'::regclass)").isPresent(), is(false));
	}

	@Test
	public void valueProjectionDropsOmittedSlotsAndConvertsCells() {
		DbTable table = new DbTable(null, "Widget");
		table.addColumn(portableColumn("id", PortableType.INTEGER, 1));
		table.addColumn(portableColumn("active", PortableType.BOOLEAN, 2));
		table.addColumn(portableColumn("token", PortableType.UUID, 3));
		CrossDbPlan plan = new CrossDbPlan(DatasourceType.SQLSERVER, DatasourceType.POSTGRES, new CrossDbReport());
		plan.putSourceIndexes("Widget", new int[] { 0, 2, 4 });
		DataPage page = new DataPage(0, new HashMap<>());
		page.getData().put(1, new Object[] { 1, "skip", 0, "skip2", "AABBCCDD-EEFF-0011-2233-445566778899" });

		DataPage projected = ValueAdapter.project(table, page, plan);
		Object[] row = projected.getData().get(1);
		assertThat(row, arrayWithSize(3));
		assertThat(row[0], equalTo(1));
		assertThat(row[1], equalTo(false));
		assertThat(row[2], equalTo("aabbccdd-eeff-0011-2233-445566778899"));
	}

	@Test
	public void valueAdapterConvertsBinaryEpochAndOracleBoolean() {
		assertThat(ValueAdapter.convert(PortableType.BOOLEAN, 1, DatasourceType.ORACLE), equalTo(1));
		assertThat(ValueAdapter.convert(PortableType.BOOLEAN, "0", DatasourceType.POSTGRES), equalTo(false));
		byte[] bytes = (byte[]) ValueAdapter.convert(PortableType.BINARY, "YWE=", DatasourceType.POSTGRES);
		assertThat(new String(bytes), equalTo("aa"));
		String timestamp = (String) ValueAdapter.convert(PortableType.TIMESTAMP, 1_577_836_800_000L, DatasourceType.SQLSERVER);
		assertThat(timestamp, equalTo("2020-01-01T00:00:00"));
		String zoned = (String) ValueAdapter.convert(PortableType.TIMESTAMPTZ, List.of(2020, 1, 2, 3, 4, 5), DatasourceType.POSTGRES);
		assertThat(zoned, equalTo("2020-01-02T03:04:05Z"));
		assertThat(ValueAdapter.convert(PortableType.DATE, "2020-05-06", DatasourceType.MYSQL), equalTo("2020-05-06"));
	}

	@Test
	public void identityFlags() {
		assertThat(IdentityFlags.postgres("YES", null), is(true));
		assertThat(IdentityFlags.postgres("NO", "nextval('public.t_id_seq'::regclass)"), is(true));
		assertThat(IdentityFlags.postgres("NO", "0"), is(false));
		assertThat(IdentityFlags.mysql("auto_increment"), is(true));
		assertThat(IdentityFlags.mysql(""), is(false));
		assertThat(IdentityFlags.oracle("YES"), is(true));
		assertThat(IdentityFlags.oracle("NO"), is(false));
	}

	private static DbModel sqlServerModel() {
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.SQLSERVER.name());
		model.setSchemas(Set.of("dbo"));
		DbTable table = model.addTable("Widget", "dbo");
		table.addColumn(DbColumn.builder().name("id").dataType("int").columnType("int").ordinalPosition(1)
			.nullable(false).identity(true).build());
		table.addColumn(DbColumn.builder().name("name").dataType("nvarchar").columnType("nvarchar").ordinalPosition(2)
			.maxLength(50).nullable(true).columnDefault("(getdate())").build());
		table.addColumn(DbColumn.builder().name("active").dataType("bit").columnType("bit").ordinalPosition(3)
			.nullable(false).columnDefault("((0))").build());
		table.addColumn(DbColumn.builder().name("geo").dataType("geography").columnType("geography").ordinalPosition(4)
			.nullable(true).build());
		table.addColumn(DbColumn.builder().name("token").dataType("uniqueidentifier").columnType("uniqueidentifier")
			.ordinalPosition(5).nullable(true).build());
		table.getPkColumnNames().add("id");
		model.getViews().add(DbView.builder().schema("dbo").name("WidgetView").definition("select 1").build());
		return model;
	}

	private static DbColumn column(String name, String type, int ordinal) {
		return DbColumn.builder().name(name).dataType(type).columnType(type).ordinalPosition(ordinal).nullable(true).build();
	}

	private static DbColumn portableColumn(String name, PortableType type, int ordinal) {
		DbColumn column = DbColumn.builder().name(name).ordinalPosition(ordinal).nullable(true).build();
		column.setPortable(PortableColumn.builder().type(type).nullable(true).build());
		return column;
	}

	private static String joined(CrossDbPlan plan) {
		return String.join("\n", plan.report().warnings());
	}

	private static String joinedReport(CrossDbReport report) {
		return String.join("\n", report.warnings());
	}
}
