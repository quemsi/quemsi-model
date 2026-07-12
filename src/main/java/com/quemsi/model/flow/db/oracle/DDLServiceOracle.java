package com.quemsi.model.flow.db.oracle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.quemsi.commons.util.CommonOps;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.CheckConstraint;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.diff.DbCheckConstraintDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbColumnDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbForeignKeyDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbIndexDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbModelDiff;
import com.quemsi.model.flow.db.sql.diff.DbModelDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbSequenceDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbTableDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbUniqueConstraintDiffOp;
import com.quemsi.model.flow.db.sql.diff.DiffEntityType;
import com.quemsi.model.flow.db.sql.diff.DiffOpType;
import com.quemsi.model.util.CommonHelpers;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class DDLServiceOracle implements DDLService {
	private Connection conn;

	@Override
	public boolean dropTables(String... tableNames) {
		try {
			Statement s = conn.createStatement();
			for (String tableName : tableNames) {
				s.addBatch("DROP TABLE " + tableName + " PURGE");
			}
			s.executeBatch();
			return true;
		} catch (Exception e) {
			throw Exceptions.server("failed-to-clear-tables").withCause(e).get();
		}
	}

	@Override
	public boolean dropSequences(String... sequenceNames) {
		try {
			Statement s = conn.createStatement();
			for (String sequenceName : sequenceNames) {
				s.addBatch("DROP SEQUENCE " + sequenceName);
			}
			s.executeBatch();
			return true;
		} catch (Exception e) {
			throw Exceptions.server("failed-to-clear-sequences").withCause(e).get();
		}
	}

	public LinkedList<String> tables(Set<String> schemas) {
		try (PreparedStatement ps = conn.prepareStatement(CommonHelpers.addInParameter(DatasourceFactoryOracle.SQL_FOR_TABLES, schemas.size()))) {
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ps.setString(i, schema)));
			ResultSet rs = ps.executeQuery();
			LinkedList<String> tables = new LinkedList<>();
			while (rs.next()) {
				String schemaName = rs.getString("SCHEMA_NAME");
				String tableName = rs.getString("TABLE_NAME");
				tables.add(CommonHelpers.qualifiedName(schemaName, tableName));
			}
			return tables;
		} catch (Exception e) {
			throw Exceptions.server("unable-to-get-tables").withCause(e).get();
		}
	}

	public LinkedList<String> sequences(Set<String> schemas) {
		try (PreparedStatement ps = conn.prepareStatement(CommonHelpers.addInParameter(DatasourceFactoryOracle.SQL_FOR_SEQUENCES, schemas.size()))) {
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ps.setString(i, schema)));
			ResultSet rs = ps.executeQuery();
			LinkedList<String> seqs = new LinkedList<>();
			while (rs.next()) {
				String schemaName = rs.getString("SCHEMA_NAME");
				String sequenceName = rs.getString("SEQUENCE_NAME");
				seqs.add(CommonHelpers.qualifiedName(schemaName, sequenceName));
			}
			return seqs;
		} catch (Exception e) {
			throw Exceptions.server("unable-to-get-sequences").withCause(e).get();
		}
	}

	@Override
	public void disableConstraints(Set<ReferenceInfo> constraints) {
		for (ReferenceInfo refInfo : constraints) {
			StringBuilder sb = new StringBuilder("ALTER TABLE ");
			sb.append(refInfo.srcQualifiedName()).append(" DISABLE CONSTRAINT ");
			appendQuoted(sb, refInfo.getConstraintName());
			try {
				String sql = sb.toString();
				log.info("disable constraint sql :{}", sql);
				Statement s = conn.createStatement();
				s.executeUpdate(sql);
			} catch (SQLException ignore) {
				log.info("ignored disable constraint " + refInfo.getConstraintName(), ignore);
			}
		}
	}

	@Override
	public void enableContraints(Set<ReferenceInfo> constraints) {
		for (ReferenceInfo refInfo : constraints) {
			StringBuilder sb = new StringBuilder("ALTER TABLE ");
			sb.append(refInfo.srcQualifiedName()).append(" ENABLE CONSTRAINT ");
			appendQuoted(sb, refInfo.getConstraintName());
			try {
				String sql = sb.toString();
				log.info("enable constraint sql :{}", sql);
				Statement s = conn.createStatement();
				s.executeUpdate(sql);
			} catch (SQLException ignore) {
				log.info("ignored enable constraint " + refInfo.getConstraintName(), ignore);
			}
		}
	}

	private String columnType(String type, Integer maxLength, Integer precision, Integer scale) {
		String normalized = type == null ? "" : type.toLowerCase();
		if (Set.of("varchar", "varchar2", "nvarchar2", "char", "nchar").contains(normalized) && maxLength != null) {
			return normalized.toUpperCase() + "(" + maxLength + ")";
		}
		if (Set.of("number", "numeric", "decimal").contains(normalized) && precision != null) {
			if (scale != null) {
				return "NUMBER(" + precision + "," + scale + ")";
			}
			return "NUMBER(" + precision + ")";
		}
		return type == null ? "VARCHAR2(4000)" : type.toUpperCase();
	}

	private StringBuilder escape(StringBuilder sb, String columnName) {
		if (needsQuoting(columnName)) {
			appendQuoted(sb, columnName);
		} else {
			sb.append(columnName);
		}
		return sb;
	}

	private boolean needsQuoting(String name) {
		return DatasourceFactoryOracle.RESERVED_KEYS.contains(name.toUpperCase())
			|| !name.equals(name.toUpperCase());
	}

	private static void appendQuoted(StringBuilder sb, String name) {
		sb.append('"').append(name.replace("\"", "\"\"")).append('"');
	}

	private static String quoted(String name) {
		StringBuilder sb = new StringBuilder();
		appendQuoted(sb, name);
		return sb.toString();
	}

	@Override
	public void createTables(DbModel dbModel) {
		LinkedList<StringBuilder> scripts = new LinkedList<>();
		Map<String, List<ReferenceInfo>> tableReferences = dbModel.getReferenceInfos().stream()
			.collect(Collectors.groupingBy(ReferenceInfo::srcQualifiedName));
		Set<String> existingTables = new HashSet<>(tables(dbModel.getSchemas()));
		Set<String> sequences = new HashSet<>(sequences(dbModel.getSchemas()));
		if (!dbModel.getSequences().isEmpty()) {
			for (DbSequence seq : dbModel.getSequences()) {
				if (sequences.contains(seq.qualifiedName())) {
					continue;
				}
				StringBuilder seqBuilder = new StringBuilder("CREATE SEQUENCE ");
				seqBuilder.append(seq.qualifiedName());
				seqBuilder.append(" START WITH ");
				if (seq.getLastValue() == null) {
					seqBuilder.append(seq.getStartValue());
				} else {
					seqBuilder.append(seq.getLastValue() + 1L);
				}
				seqBuilder.append(" INCREMENT BY ").append(seq.getIncrementBy());
				if (seq.getMinValue() != null && seq.getMinValue() > Long.MIN_VALUE) {
					seqBuilder.append(" MINVALUE ").append(seq.getMinValue());
				}
				if (seq.getMaxValue() != null && seq.getMaxValue() < Long.MAX_VALUE) {
					seqBuilder.append(" MAXVALUE ").append(seq.getMaxValue());
				}
				if (seq.isCycle()) {
					seqBuilder.append(" CYCLE");
				} else {
					seqBuilder.append(" NOCYCLE");
				}
				if (seq.getCacheSize() != null && seq.getCacheSize() > 0L) {
					seqBuilder.append(" CACHE ").append(seq.getCacheSize());
				} else {
					seqBuilder.append(" NOCACHE");
				}
				seqBuilder.append(";");
				log.info("sequence sql : {}", seqBuilder);
				scripts.add(seqBuilder);
			}
		}
		for (String tableName : dbModel.orderedTableNames()) {
			if (existingTables.contains(tableName)) {
				log.info("table {} already exists in schema {} skipping", tableName, dbModel.getSchemas());
				continue;
			}
			DbTable table = dbModel.findTable(tableName).orElseThrow();
			StringBuilder sb = new StringBuilder("CREATE TABLE ").append(tableName).append(" (").append(System.lineSeparator());
			DbColumn[] columns = table.orderedColumns();
			for (DbColumn c : columns) {
				sb.append("  ");
				escape(sb, c.getName()).append(" ").append(columnType(c.getDataType(), c.getMaxLength(), c.getNumPrecision(), c.getNumScale()));
				if (c.isIdentity()) {
					sb.append(" GENERATED BY DEFAULT AS IDENTITY");
				}
				if (c.getColumnDefault() != null) {
					sb.append(" DEFAULT ").append(StringUtils.trimSymetric(c.getColumnDefault(), "(", ")"));
				}
				if (!c.isNullable()) {
					sb.append(" NOT NULL");
				}
				sb.append(",").append(System.lineSeparator());
			}
			if (!table.getPkColumnNames().isEmpty()) {
				sb.append("  CONSTRAINT ");
				appendQuoted(sb, table.getPkConstraintName());
				sb.append(" PRIMARY KEY (");
				appendColumnList(sb, table.getPkColumnNames());
				sb.append(")");
			}
			if (tableReferences.containsKey(tableName)) {
				for (ReferenceInfo ref : tableReferences.get(tableName)) {
					sb.append(",").append(System.lineSeparator())
						.append("  CONSTRAINT ");
					appendQuoted(sb, ref.getConstraintName());
					sb.append(" FOREIGN KEY (");
					appendColumnList(sb, ref.getSrcColumnNames());
					sb.append(") REFERENCES ").append(ref.refQualifiedName()).append(" (");
					appendColumnList(sb, ref.getRefColumnNames());
					sb.append(")");
				}
			}
			sb.append(System.lineSeparator()).append(")");
			log.info("create script for {} : {}", tableName, sb);
			scripts.add(sb);
			if (dbModel.getIndexes().containsKey(tableName)) {
				Map<String, IndexInfo> indexes = dbModel.getIndexes().get(tableName);
				for (String indName : indexes.keySet()) {
					IndexInfo indCols = indexes.get(indName);
					StringBuilder indBuilder = new StringBuilder("CREATE ");
					if (indCols.isUnique()) {
						indBuilder.append("UNIQUE ");
					}
					indBuilder.append("INDEX ").append(indCols.getIndexName()).append(" ON ");
					indBuilder.append(tableName).append(" (");
					appendColumnList(indBuilder, indCols.getColumns());
					indBuilder.append(")");
					log.info("index sql : {}", indBuilder);
					scripts.add(indBuilder);
				}
			}
		}
		for (ContraintInfo contraintInfo : dbModel.getContraintInfos()) {
			StringBuilder sb = new StringBuilder("ALTER TABLE ").append(contraintInfo.qualifiedTableName()).append(" ADD CONSTRAINT ");
			appendQuoted(sb, contraintInfo.getConstraintName());
			sb.append(" UNIQUE (");
			appendColumnList(sb, contraintInfo.getColumnNames());
			sb.append(")");
			log.info("create unique constraint {} for table {} : {}", contraintInfo.getConstraintName(), contraintInfo.qualifiedTableName(), sb);
			scripts.add(sb);
		}
		for (CheckConstraint checkConstraint : dbModel.getCheckConstraints()) {
			StringBuilder sb = new StringBuilder("ALTER TABLE ").append(checkConstraint.qualifiedTableName()).append(" ADD CONSTRAINT ");
			appendQuoted(sb, checkConstraint.getConstraintName());
			sb.append(" CHECK ").append(checkConstraint.getCondef());
			log.info("create check constraint {} for table {} : {}", checkConstraint.getConstraintName(), checkConstraint.qualifiedTableName(), sb);
			scripts.add(sb);
		}
		try {
			for (String schema : dbModel.getSchemas()) {
				if (!checkSchema(schema)) {
					log.warn("Oracle schema/user {} does not exist; skipping schema creation", schema);
				}
			}
			Statement s = conn.createStatement();
			for (StringBuilder sb : scripts) {
				log.info("sql : {}", sb);
				s.executeUpdate(sb.toString());
			}
		} catch (SQLException e) {
			throw Exceptions.server("failed-to-create-tables").withCause(e).get();
		}
	}


	@Override
	public boolean checkSchema(String schema) throws SQLException {
		try (PreparedStatement ss = conn.prepareStatement(DatasourceFactoryOracle.SQL_FOR_SCHEMA)) {
			ss.setString(1, schema.toUpperCase());
			ResultSet rss = ss.executeQuery();
			return rss.next();
		}
	}

	@Override
	public void close() throws Exception {
		if (conn != null) {
			conn.close();
		}
	}

	@Override
	public List<String> ddlFrom(DbModelDiff diff, DbModel dbModel) {
		List<String> statements = new ArrayList<>();
		if (diff == null || diff.getOperations() == null || diff.getOperations().isEmpty()) {
			return statements;
		}
		for (DbModelDiffOp operation : diff.getOperations()) {
			statements.addAll(generateSqlForOperation(operation, dbModel));
		}
		return statements;
	}

	private List<String> generateSqlForOperation(DbModelDiffOp operation, DbModel dbModel) {
		List<String> statements = new ArrayList<>();
		DiffEntityType entityType = operation.getEntityType();
		DiffOpType opType = operation.getOpType();
		switch (entityType) {
			case TABLE -> statements.addAll(generateTableSql((DbTableDiffOp) operation, opType, dbModel));
			case COLUMN -> statements.addAll(generateColumnSql((DbColumnDiffOp) operation, opType));
			case FOREIGN_KEY -> statements.addAll(generateForeignKeySql((DbForeignKeyDiffOp) operation, opType));
			case UNIQUE_CONSTRAINT -> statements.addAll(generateUniqueConstraintSql((DbUniqueConstraintDiffOp) operation, opType));
			case CHECK_CONSTRAINT -> statements.addAll(generateCheckConstraintSql((DbCheckConstraintDiffOp) operation, opType));
			case INDEX -> statements.addAll(generateIndexSql((DbIndexDiffOp) operation, opType));
			case SEQUENCE -> statements.addAll(generateSequenceSql((DbSequenceDiffOp) operation, opType));
			default -> {
			}
		}
		return statements;
	}

	private List<String> generateTableSql(DbTableDiffOp operation, DiffOpType opType, DbModel dbModel) {
		List<String> statements = new ArrayList<>();
		switch (opType) {
			case CREATE -> {
				if (operation.getNewTable() != null) {
					statements.add(generateCreateTableSql(operation.getNewTable(), dbModel));
				}
			}
			case DROP -> {
				if (operation.getOldTable() != null) {
					statements.add("DROP TABLE " + operation.getQualifiedName() + " PURGE;");
				}
			}
			case MODIFY -> {
				if (operation.getOldTable() != null) {
					statements.add("DROP TABLE " + operation.getQualifiedName() + " PURGE;");
				}
				if (operation.getNewTable() != null) {
					statements.add(generateCreateTableSql(operation.getNewTable(), dbModel));
				}
			}
			default -> {
			}
		}
		return statements;
	}

	private String generateCreateTableSql(DbTable table, DbModel dbModel) {
		String tableName = table.qualifiedName();
		Map<String, List<ReferenceInfo>> tableReferences = dbModel.getReferenceInfos().stream()
			.collect(Collectors.groupingBy(ReferenceInfo::srcQualifiedName));
		StringBuilder sb = new StringBuilder("CREATE TABLE ").append(tableName).append(" (").append(System.lineSeparator());
		for (DbColumn c : table.orderedColumns()) {
			sb.append("  ");
			escape(sb, c.getName()).append(" ").append(columnType(c.getDataType(), c.getMaxLength(), c.getNumPrecision(), c.getNumScale()));
			if (c.isIdentity()) {
				sb.append(" GENERATED BY DEFAULT AS IDENTITY");
			}
			if (c.getColumnDefault() != null) {
				sb.append(" DEFAULT ").append(StringUtils.trimSymetric(c.getColumnDefault(), "(", ")"));
			}
			if (!c.isNullable()) {
				sb.append(" NOT NULL");
			}
			sb.append(",").append(System.lineSeparator());
		}
		if (!table.getPkColumnNames().isEmpty()) {
			sb.append("  CONSTRAINT ");
			appendQuoted(sb, table.getPkConstraintName());
			sb.append(" PRIMARY KEY (");
			appendColumnList(sb, table.getPkColumnNames());
			sb.append(")");
		}
		if (tableReferences.containsKey(tableName)) {
			for (ReferenceInfo ref : tableReferences.get(tableName)) {
				sb.append(",").append(System.lineSeparator())
					.append("  CONSTRAINT ");
				appendQuoted(sb, ref.getConstraintName());
				sb.append(" FOREIGN KEY (");
				appendColumnList(sb, ref.getSrcColumnNames());
				sb.append(") REFERENCES ").append(ref.refQualifiedName()).append(" (");
				appendColumnList(sb, ref.getRefColumnNames());
				sb.append(")");
			}
		}
		sb.append(System.lineSeparator()).append(")");
		return sb.toString();
	}

	private void appendColumnList(StringBuilder sb, Iterable<String> columnNames) {
		Iterator<String> cIt = columnNames.iterator();
		while (cIt.hasNext()) {
			String cName = cIt.next();
			if (needsQuoting(cName)) {
				appendQuoted(sb, cName);
			} else {
				sb.append(cName);
			}
			if (cIt.hasNext()) {
				sb.append(", ");
			}
		}
	}

	private List<String> generateColumnSql(DbColumnDiffOp operation, DiffOpType opType) {
		List<String> statements = new ArrayList<>();
		String tableName = operation.getTableQualifiedName();
		String columnName = operation.getColumnName();
		switch (opType) {
			case CREATE -> {
				if (operation.getNewColumn() != null) {
					statements.add(generateAddColumnSql(tableName, operation.getNewColumn()));
				}
			}
			case DROP -> statements.add("ALTER TABLE " + tableName + " DROP COLUMN " + quoted(columnName) + ";");
			case MODIFY -> {
				if (operation.getOldColumn() != null && operation.getNewColumn() != null) {
					statements.addAll(generateModifyColumnSql(tableName, operation.getOldColumn(), operation.getNewColumn()));
				}
			}
			default -> {
			}
		}
		return statements;
	}

	private String generateAddColumnSql(String tableName, DbColumn column) {
		StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName).append(" ADD (");
		escape(sb, column.getName()).append(" ").append(columnType(column.getDataType(), column.getMaxLength(), column.getNumPrecision(), column.getNumScale()));
		if (column.isIdentity()) {
			sb.append(" GENERATED BY DEFAULT AS IDENTITY");
		}
		if (column.getColumnDefault() != null) {
			sb.append(" DEFAULT ").append(StringUtils.trimSymetric(column.getColumnDefault(), "(", ")"));
		}
		if (!column.isNullable()) {
			sb.append(" NOT NULL");
		}
		sb.append(")");
		return sb.toString();
	}

	private List<String> generateModifyColumnSql(String tableName, DbColumn oldColumn, DbColumn newColumn) {
		List<String> statements = new ArrayList<>();
		if (!Objects.equals(oldColumn.getColumnType(), newColumn.getColumnType())
			|| !Objects.equals(oldColumn.getMaxLength(), newColumn.getMaxLength())
			|| !Objects.equals(oldColumn.getNumPrecision(), newColumn.getNumPrecision())
			|| !Objects.equals(oldColumn.getNumScale(), newColumn.getNumScale())
			|| oldColumn.isNullable() != newColumn.isNullable()) {
			StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName).append(" MODIFY (");
			escape(sb, newColumn.getName()).append(" ").append(columnType(newColumn.getDataType(), newColumn.getMaxLength(), newColumn.getNumPrecision(), newColumn.getNumScale()));
			if (!newColumn.isNullable()) {
				sb.append(" NOT NULL");
			}
			sb.append(")");
			statements.add(sb.toString());
		}
		if (!Objects.equals(oldColumn.getColumnDefault(), newColumn.getColumnDefault())) {
			if (newColumn.getColumnDefault() == null) {
				statements.add("ALTER TABLE " + tableName + " MODIFY (" + quoted(newColumn.getName()) + " DEFAULT NULL)");
			} else {
				statements.add("ALTER TABLE " + tableName + " MODIFY (" + quoted(newColumn.getName()) + " DEFAULT "
					+ StringUtils.trimSymetric(newColumn.getColumnDefault(), "(", ")") + ")");
			}
		}
		return statements;
	}

	private List<String> generateForeignKeySql(DbForeignKeyDiffOp operation, DiffOpType opType) {
		List<String> statements = new ArrayList<>();
		switch (opType) {
			case CREATE -> {
				if (operation.getNewReference() != null) {
					statements.add(generateAddForeignKeySql(operation.getNewReference()));
				}
			}
			case DROP -> {
				if (operation.getOldReference() != null) {
					ReferenceInfo ref = operation.getOldReference();
					statements.add("ALTER TABLE " + ref.srcQualifiedName() + " DROP CONSTRAINT " + quoted(ref.getConstraintName()) + ";");
				}
			}
			case MODIFY -> {
				if (operation.getOldReference() != null) {
					ReferenceInfo ref = operation.getOldReference();
					statements.add("ALTER TABLE " + ref.srcQualifiedName() + " DROP CONSTRAINT " + quoted(ref.getConstraintName()) + ";");
				}
				if (operation.getNewReference() != null) {
					statements.add(generateAddForeignKeySql(operation.getNewReference()));
				}
			}
			default -> {
			}
		}
		return statements;
	}

	private String generateAddForeignKeySql(ReferenceInfo ref) {
		StringBuilder sb = new StringBuilder("ALTER TABLE ").append(ref.srcQualifiedName())
			.append(" ADD CONSTRAINT ");
		appendQuoted(sb, ref.getConstraintName());
		sb.append(" FOREIGN KEY (");
		appendColumnList(sb, ref.getSrcColumnNames());
		sb.append(") REFERENCES ").append(ref.refQualifiedName()).append(" (");
		appendColumnList(sb, ref.getRefColumnNames());
		sb.append(")");
		return sb.toString();
	}

	private List<String> generateUniqueConstraintSql(DbUniqueConstraintDiffOp operation, DiffOpType opType) {
		List<String> statements = new ArrayList<>();
		switch (opType) {
			case CREATE -> {
				if (operation.getNewConstraint() != null) {
					statements.add(generateAddUniqueConstraintSql(operation.getNewConstraint()));
				}
			}
			case DROP -> {
				if (operation.getOldConstraint() != null) {
					ContraintInfo constraint = operation.getOldConstraint();
					statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + quoted(constraint.getConstraintName()) + ";");
				}
			}
			case MODIFY -> {
				if (operation.getOldConstraint() != null) {
					ContraintInfo constraint = operation.getOldConstraint();
					statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + quoted(constraint.getConstraintName()) + ";");
				}
				if (operation.getNewConstraint() != null) {
					statements.add(generateAddUniqueConstraintSql(operation.getNewConstraint()));
				}
			}
			default -> {
			}
		}
		return statements;
	}

	private String generateAddUniqueConstraintSql(ContraintInfo constraint) {
		StringBuilder sb = new StringBuilder("ALTER TABLE ").append(constraint.qualifiedTableName())
			.append(" ADD CONSTRAINT ");
		appendQuoted(sb, constraint.getConstraintName());
		sb.append(" UNIQUE (");
		appendColumnList(sb, constraint.getColumnNames());
		sb.append(")");
		return sb.toString();
	}

	private List<String> generateCheckConstraintSql(DbCheckConstraintDiffOp operation, DiffOpType opType) {
		List<String> statements = new ArrayList<>();
		switch (opType) {
			case CREATE -> {
				if (operation.getNewConstraint() != null) {
					CheckConstraint constraint = operation.getNewConstraint();
					statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " ADD CONSTRAINT " + quoted(constraint.getConstraintName()) + " CHECK " + constraint.getCondef() + ";");
				}
			}
			case DROP -> {
				if (operation.getOldConstraint() != null) {
					CheckConstraint constraint = operation.getOldConstraint();
					statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + quoted(constraint.getConstraintName()) + ";");
				}
			}
			case MODIFY -> {
				if (operation.getOldConstraint() != null) {
					CheckConstraint constraint = operation.getOldConstraint();
					statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + quoted(constraint.getConstraintName()) + ";");
				}
				if (operation.getNewConstraint() != null) {
					CheckConstraint constraint = operation.getNewConstraint();
					statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " ADD CONSTRAINT " + quoted(constraint.getConstraintName()) + " CHECK " + constraint.getCondef() + ";");
				}
			}
			default -> {
			}
		}
		return statements;
	}

	private List<String> generateIndexSql(DbIndexDiffOp operation, DiffOpType opType) {
		List<String> statements = new ArrayList<>();
		switch (opType) {
			case CREATE -> {
				if (operation.getNewIndex() != null) {
					statements.add(generateCreateIndexSql(operation.getNewIndex(), operation.getTableQualifiedName()));
				}
			}
			case DROP -> {
				if (operation.getOldIndex() != null) {
					statements.add("DROP INDEX " + operation.getOldIndex().getIndexName() + ";");
				}
			}
			case MODIFY -> {
				if (operation.getOldIndex() != null) {
					statements.add("DROP INDEX " + operation.getOldIndex().getIndexName() + ";");
				}
				if (operation.getNewIndex() != null) {
					statements.add(generateCreateIndexSql(operation.getNewIndex(), operation.getTableQualifiedName()));
				}
			}
			default -> {
			}
		}
		return statements;
	}

	private String generateCreateIndexSql(IndexInfo index, String tableQualifiedName) {
		StringBuilder sb = new StringBuilder("CREATE ");
		if (index.isUnique()) {
			sb.append("UNIQUE ");
		}
		sb.append("INDEX ").append(index.getIndexName()).append(" ON ").append(tableQualifiedName).append(" (");
		appendColumnList(sb, index.getColumns());
		sb.append(")");
		return sb.toString();
	}

	private List<String> generateSequenceSql(DbSequenceDiffOp operation, DiffOpType opType) {
		List<String> statements = new ArrayList<>();
		switch (opType) {
			case CREATE -> {
				if (operation.getNewSequence() != null) {
					statements.add(generateCreateSequenceSql(operation.getNewSequence()));
				}
			}
			case DROP -> {
				if (operation.getOldSequence() != null) {
					statements.add("DROP SEQUENCE " + operation.getQualifiedName() + ";");
				}
			}
			case MODIFY -> {
				if (operation.getOldSequence() != null && operation.getNewSequence() != null) {
					statements.addAll(generateAlterSequenceSql(operation.getOldSequence(), operation.getNewSequence()));
				}
			}
			default -> {
			}
		}
		return statements;
	}

	private String generateCreateSequenceSql(DbSequence seq) {
		StringBuilder sb = new StringBuilder("CREATE SEQUENCE ");
		sb.append(seq.qualifiedName());
		sb.append(" START WITH ");
		if (seq.getLastValue() == null) {
			sb.append(seq.getStartValue());
		} else {
			sb.append(seq.getLastValue() + 1L);
		}
		sb.append(" INCREMENT BY ").append(seq.getIncrementBy());
		if (seq.getMinValue() != null && seq.getMinValue() > Long.MIN_VALUE) {
			sb.append(" MINVALUE ").append(seq.getMinValue());
		}
		if (seq.getMaxValue() != null && seq.getMaxValue() < Long.MAX_VALUE) {
			sb.append(" MAXVALUE ").append(seq.getMaxValue());
		}
		if (seq.isCycle()) {
			sb.append(" CYCLE");
		} else {
			sb.append(" NOCYCLE");
		}
		if (seq.getCacheSize() != null && seq.getCacheSize() > 0L) {
			sb.append(" CACHE ").append(seq.getCacheSize());
		} else {
			sb.append(" NOCACHE");
		}
		return sb.toString();
	}

	private List<String> generateAlterSequenceSql(DbSequence oldSeq, DbSequence newSeq) {
		List<String> statements = new ArrayList<>();
		StringBuilder sb = new StringBuilder("ALTER SEQUENCE ").append(newSeq.qualifiedName());
		boolean hasChanges = false;
		if (!Objects.equals(oldSeq.getIncrementBy(), newSeq.getIncrementBy()) && newSeq.getIncrementBy() != null) {
			sb.append(" INCREMENT BY ").append(newSeq.getIncrementBy());
			hasChanges = true;
		}
		if (!Objects.equals(oldSeq.getMinValue(), newSeq.getMinValue())) {
			if (newSeq.getMinValue() != null && newSeq.getMinValue() > Long.MIN_VALUE) {
				sb.append(" MINVALUE ").append(newSeq.getMinValue());
			}
			hasChanges = true;
		}
		if (!Objects.equals(oldSeq.getMaxValue(), newSeq.getMaxValue())) {
			if (newSeq.getMaxValue() != null && newSeq.getMaxValue() < Long.MAX_VALUE) {
				sb.append(" MAXVALUE ").append(newSeq.getMaxValue());
			}
			hasChanges = true;
		}
		if (!Objects.equals(oldSeq.getLastValue(), newSeq.getLastValue())
			|| !Objects.equals(oldSeq.getStartValue(), newSeq.getStartValue())) {
			if (newSeq.getLastValue() != null) {
				sb.append(" RESTART START WITH ").append(newSeq.getLastValue() + 1L);
			} else if (newSeq.getStartValue() != null) {
				sb.append(" RESTART START WITH ").append(newSeq.getStartValue());
			}
			hasChanges = true;
		}
		if (!Objects.equals(oldSeq.getCacheSize(), newSeq.getCacheSize())) {
			if (newSeq.getCacheSize() != null && newSeq.getCacheSize() > 0L) {
				sb.append(" CACHE ").append(newSeq.getCacheSize());
			} else {
				sb.append(" NOCACHE");
			}
			hasChanges = true;
		}
		if (oldSeq.isCycle() != newSeq.isCycle()) {
			sb.append(newSeq.isCycle() ? " CYCLE" : " NOCYCLE");
			hasChanges = true;
		}
		if (hasChanges) {
			statements.add(sb.toString());
		}
		return statements;
	}

	@Override
	public void executeSql(String sql) throws SQLException {
		if (sql == null || sql.trim().isEmpty()) {
			return;
		}
		try {
			Statement s = conn.createStatement();
			s.execute(sql);
			log.debug("Executed SQL: {}", sql);
		} catch (SQLException e) {
			log.error("Failed to execute SQL: {}", sql, e);
			throw e;
		}
	}
}
