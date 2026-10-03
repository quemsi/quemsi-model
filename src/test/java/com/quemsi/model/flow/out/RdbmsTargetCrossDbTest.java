package com.quemsi.model.flow.out;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.Flow;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.diff.DbModelDiff;
import com.quemsi.model.flow.file.DirectoryBackupArchive;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataMeta;
import com.quemsi.model.flow.in.TableDataPage;
import com.quemsi.model.flow.subset.SubsetBrowseResult;
import com.quemsi.model.util.CommonConstants;
import com.quemsi.model.util.CommonHelpers;

public class RdbmsTargetCrossDbTest {

	@TempDir
	Path temp;

	@Test
	public void sqlServerBackupRestoresOntoPostgres() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.SQLSERVER.name());
		model.setSchemas(Set.of("dbo"));
		model.setBatchSize(10);
		DbTable table = model.addTable("Widget", "dbo");
		table.addColumn(DbColumn.builder().name("id").dataType("int").columnType("int").ordinalPosition(1)
			.nullable(false).identity(true).build());
		table.addColumn(DbColumn.builder().name("name").dataType("nvarchar").columnType("nvarchar").ordinalPosition(2)
			.maxLength(50).nullable(true).build());
		table.addColumn(DbColumn.builder().name("active").dataType("bit").columnType("bit").ordinalPosition(3)
			.nullable(false).build());
		table.addColumn(DbColumn.builder().name("geo").dataType("geography").columnType("geography").ordinalPosition(4)
			.nullable(true).build());
		table.getPkColumnNames().add("id");
		mapper.writeValue(temp.resolve(CommonConstants.DB_MODEL_FILE_NAME).toFile(), model);

		String qualified = "dbo.Widget";
		Path meta = temp.resolve(CommonHelpers.tableMetaEntryName(qualified));
		Files.createDirectories(meta.getParent());
		mapper.writeValue(meta.toFile(), TableDataMeta.builder().tableName(qualified).totalPages(1).pageSize(10).build());
		DataPage page = new DataPage(0, new LinkedHashMap<>());
		page.getData().put(1, new Object[] { 7, "Ada", 1, "shape" });
		Path pagePath = temp.resolve(CommonHelpers.tablePageEntryName(qualified, 0));
		Files.createDirectories(pagePath.getParent());
		mapper.writeValue(pagePath.toFile(), page);

		RecordingDdl ddl = new RecordingDdl();
		RecordingDml dml = new RecordingDml();
		RdbmsTarget target = new RdbmsTarget();
		target.setDatasourceFactory(new FixedFactory(DatasourceType.POSTGRES, ddl, dml));
		target.setObjectMapper(mapper);
		target.setParallelism(1);
		FlowContext context = new FlowContext(new Flow(), 1L);
		context.setBackupArchive(DirectoryBackupArchive.open(temp));

		target.store(context, "db", List.of(), 1L);

		DbColumn[] columns = ddl.model.getTables().get("dbo.Widget").orderedColumns();
		assertThat(columns.length, equalTo(3));
		assertThat(columns[0].getColumnType(), equalTo("int4"));
		assertThat(columns[0].isIdentity(), equalTo(true));
		assertThat(columns[1].getColumnType(), equalTo("varchar"));
		assertThat(columns[2].getColumnType(), equalTo("bool"));
		Object[] row = dml.page.getData().values().iterator().next();
		assertThat(row.length, equalTo(3));
		assertThat(row[0], equalTo(7));
		assertThat(row[1], equalTo("Ada"));
		assertThat(row[2], instanceOf(Boolean.class));
		assertThat(row[2], equalTo(true));
		assertThat(dml.reseeded, equalTo("dbo.Widget.id"));
	}

	@Test
	public void mongodbStillRejected() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		DbModel model = new DbModel();
		model.setSourceType(DatasourceType.MONGODB.name());
		mapper.writeValue(temp.resolve(CommonConstants.DB_MODEL_FILE_NAME).toFile(), model);
		RdbmsTarget target = new RdbmsTarget();
		target.setDatasourceFactory(new FixedFactory(DatasourceType.POSTGRES, new RecordingDdl(), new RecordingDml()));
		target.setObjectMapper(mapper);
		target.setParallelism(1);
		FlowContext context = new FlowContext(new Flow(), 1L);
		context.setBackupArchive(DirectoryBackupArchive.open(temp));

		BaseRuntimeException ex = assertThrows(BaseRuntimeException.class,
			() -> target.store(context, "db", List.of(), 1L));
		assertThat(ex.getMessage(), containsString("unsupported-source-type-for-rdbms-target"));
	}

	private static final class FixedFactory implements DataSourceFactory {
		private final DatasourceType type;
		private final DDLService ddl;
		private final DMLService dml;

		private FixedFactory(DatasourceType type, DDLService ddl, DMLService dml) {
			this.type = type;
			this.ddl = ddl;
			this.dml = dml;
		}

		@Override public String getName() { return type.name(); }
		@Override public void setName(String name) {}
		@Override public String getDbName() { return "db"; }
		@Override public void setDbName(String dbName) {}
		@Override public Set<String> getSchemas() { return Set.of(); }
		@Override public void setSchemas(Set<String> schemas) {}
		@Override public String getUrl() { return ""; }
		@Override public void setUrl(String url) {}
		@Override public String getUsername() { return ""; }
		@Override public void setUsername(String username) {}
		@Override public String getPassword() { return ""; }
		@Override public void setPassword(String password) {}
		@Override public boolean isReadOnly() { return false; }
		@Override public void setReadOnly(boolean readOnly) {}
		@Override public DataSource getDataSource() { return null; }
		@Override public DbModel getDbModel(Consumer<LogMessage> progress) { return new DbModel(); }
		@Override public DDLService ddlService() { return ddl; }
		@Override public DMLService dmlService() { return dml; }
		@Override public DatasourceType type() { return type; }
	}

	private static final class RecordingDdl implements DDLService {
		private DbModel model;

		@Override public boolean dropTables(String... tableNames) { return true; }
		@Override public boolean dropSequences(String... sequenceNames) { return true; }
		@Override public boolean dropViews(String... viewNames) { return true; }
		@Override public void disableConstraints(Set<ReferenceInfo> constraints) {}
		@Override public void enableContraints(Set<ReferenceInfo> constraints) {}
		@Override public void createTables(DbModel dbModel) { this.model = dbModel; }
		@Override public void createFunctions(DbModel dbModel) {}
		@Override public void createViews(DbModel dbModel) {}
		@Override public boolean checkSchema(String schema) { return true; }
		@Override public List<String> ddlFrom(DbModelDiff diff, DbModel dbModel) { return List.of(); }
		@Override public void executeSql(String sql) {}
		@Override public void close() {}
	}

	private static final class RecordingDml implements DMLService {
		private DataPage page;
		private String reseeded;

		@Override public int getTablePageSize(Integer expectedPageSize, DbTable table) { return 10; }
		@Override public long countRows(DbTable table) { return 0; }
		@Override public TableDataPage getTableDataPage(TableDataPage.Request request) { return null; }
		@Override public int writePageData(DbTable table, DataPage dataPage) {
			this.page = dataPage;
			return dataPage.getSize();
		}
		@Override public boolean clearTables(String... tableNames) { return true; }
		@Override public void updateSequence(String qualifiedSequenceName, Long newValue) {}
		@Override public Long getMaxColumnValue(String tableName, String columnName) { return null; }
		@Override public void reseedIdentity(DbTable table, DbColumn column) {
			reseeded = table.qualifiedName() + "." + column.getName();
		}
		@Override public long countRows(DbTable table, String whereFragment) { return 0; }
		@Override public Set<String> selectPrimaryKeys(DbTable table, String whereFragment, Integer limit) { return Set.of(); }
		@Override public Set<String> selectParentPrimaryKeys(DbTable child, DbTable parent, List<String> childFkColumns,
				List<String> parentRefColumns, Collection<String> childPkKeys) { return Set.of(); }
		@Override public SubsetBrowseResult browseRows(DbTable table, String whereFragment, Integer pageSize, Integer page) { return null; }
		@Override public void close() {}
	}
}
