package com.quemsi.model.flow.db.sqlserver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.quemsi.commons.util.CommonOps;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.util.CommonHelpers;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbTable;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class DDLServiceSqlserver implements DDLService{
    private Connection conn;

    @Override
    public boolean dropTables(String... tableNames) {
        try{
			Statement s = conn.createStatement();
			for(String tableName : tableNames){
				s.addBatch("DROP TABLE IF EXISTS " + tableName);
			}
			s.executeBatch();
			return true;
		}catch(Exception e){
			e.printStackTrace();
			throw Exceptions.server("failed-to-clear-tables").withCause(e).get();
		}
    }

	public LinkedList<String> tables(String schema){
		try(
			PreparedStatement ps = conn.prepareStatement(DatasourceFactorySqlserver.SQL_FOR_TABLES);
		){
			ps.setString(1, schema);
			ResultSet rs = ps.executeQuery();
			LinkedList<String> tables = new LinkedList<>();
			while(rs.next()){
				String schemaName = rs.getString("SCHEMA_NAME");
				String tableName = rs.getString("TABLE_NAME");
				tables.add(CommonHelpers.qualifiedName(schemaName, tableName));
			}
			return tables;
		}catch(Exception e){
			throw Exceptions.server("unable-to-get-tables").withCause(e).get();
		}
	}

	public LinkedList<String> sequences(String schema){
		try(
			PreparedStatement ps = conn.prepareStatement(DatasourceFactorySqlserver.SQL_FOR_SEQUENCES);
		){
			ps.setString(1, schema);
			ResultSet rs = ps.executeQuery();
			LinkedList<String> seqs = new LinkedList<>();
			while(rs.next()){
				String schemaName = rs.getString("SCHEMA_NAME");
				String tableName = rs.getString("SEQUENCE_NAME");
				seqs.add(CommonHelpers.qualifiedName(schemaName, tableName));
			}
			return seqs;
		}catch(Exception e){
			throw Exceptions.server("unable-to-get-tables").withCause(e).get();
		}
	}

    @Override
    public void disableConstraints(Set<ReferenceInfo> constraints) {
        for(ReferenceInfo refInfo : constraints) {
            StringBuilder sb = new StringBuilder("ALTER TABLE ");
            sb.append(refInfo.getSrcSchema()) .append(refInfo.getSrcTableName()).append("NOCHECK CONSTRAINT ")
            .append(refInfo.getConstraintName()).append(";");
            try{
                String dropConstraintSql = sb.toString();
                log.info("disable constraint sql :{}", dropConstraintSql);
                Statement s = conn.createStatement();
                s.executeUpdate(dropConstraintSql);
            }catch(SQLException ignore){
                log.info("ignored disable constraint " + refInfo.getConstraintName(), ignore);
            }
        }
    }

    @Override
    public void enableContraints(Set<ReferenceInfo> constraints) {
        for(ReferenceInfo refInfo : constraints) {
            StringBuilder sb = new StringBuilder("ALTER TABLE ");
            sb.append(refInfo.getSrcSchema()) .append(refInfo.getSrcTableName()).append("WITH CHECK CHECK CONSTRAINT ")
            .append(refInfo.getConstraintName()).append(";");
            try{
                String dropConstraintSql = sb.toString();
                log.info("drop constraint sql :{}", dropConstraintSql);
                Statement s = conn.createStatement();
                s.executeUpdate(dropConstraintSql);
            }catch(SQLException ignore){
                log.info("ignored enable constraint " + refInfo.getConstraintName(), ignore);
            }
        }
    }

    private String columnType(String type, Integer maxLength){
        if("varchar".equals(type) && maxLength != null){
            return new StringBuffer(type).append("(").append(maxLength).append(")").toString();
        } else if(Set.of("varbinary", "nvarchar", "nchar").contains(type)){
            StringBuilder sb = new StringBuilder(type);
            if(maxLength != null){
                sb.append("(");
                if(maxLength == -1){
                    sb.append("MAX");
                }else{
                    sb.append(maxLength / 2);
                }
                sb.append(")");
                return sb.toString();
            }
        }
        return type;
    }
    private StringBuilder escape(StringBuilder sb, String columnName){
		if(DatasourceFactorySqlserver.RESERVED_KEYS.contains(columnName.toUpperCase())){
			sb.append("[").append(columnName).append("]");
		}else{
			sb.append(columnName);
		}
		return sb;
	}

    @Override
    public void createTables(DbModel dbModel) {
        LinkedList<StringBuilder> scripts = new LinkedList<>();
		Map<String, List<ReferenceInfo>> tableReferences = dbModel.getReferenceInfos().stream().collect(Collectors.groupingBy(r -> r.getSrcTableName()));
		Set<String> existingTables = new HashSet<>(tables(dbModel.getSchema()));
		for(String tableName : dbModel.orderedTableNames()){
			if(existingTables.contains(tableName)){
				log.info("table {} already exists in schema {} skipping", tableName, dbModel.getSchema());
				continue;
			}
			DbTable table = dbModel.findTable(tableName).orElseThrow();
			boolean hasClustedIndex = CommonOps.getOrDefault(dbModel.getIndexes(), tableName, () -> new HashMap<>())
				.values().stream().map(ii -> "CLUSTERED".equals(ii.getIndexType())).reduce(Boolean.FALSE, (a, v) -> a || v);
			StringBuilder sb = new StringBuilder("CREATE TABLE ").append(tableName).append(" (").append(System.lineSeparator());
			DbColumn[] columns = table.orderedColumns();
			for(DbColumn c : columns){
				sb.append("  ");
				escape(sb, c.getName()).append(" ").append(columnType(c.getDataType(), c.getMaxLength()));
                if(c.isIdentity()){
                    sb.append(" IDENTITY(1,1)");
                }
				if(c.getColumnDefault() != null){
                    sb.append(" DEFAULT " + StringUtils.trimSymetric(c.getColumnDefault(), "(", ")"));
                }
                if(!c.isNullable()){
					sb.append(" NOT NULL");
				} else if(c.getColumnDefault() == null){
                    if(c.isNullable() && !Set.of("TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT"
                        ,"XML", "VARBINARY", "NVARCHAR").contains(c.getColumnType().toUpperCase())){
						sb.append(" DEFAULT NULL");
					}else{
                        sb.append(" NULL");
                    }
                }
				sb.append(",").append(System.lineSeparator());
			}
			if(table.getPkColumnNames().size() > 0){
				sb.append("  CONSTRAINT ").append(table.getPkConstraintName()).append(" PRIMARY KEY ");
				if(hasClustedIndex){
					sb.append("NONCLUSTERED ");
				}
				sb.append("(");
				Iterator<String> cIt = table.getPkColumnNames().iterator();
				while(cIt.hasNext()){
					String cName = cIt.next();
					sb.append(cName);
					if(cIt.hasNext()){
						sb.append(", ");
					}
				}
				sb.append(")");
			}
			if(tableReferences.containsKey(tableName)){
				Iterator<ReferenceInfo> refIt = tableReferences.get(tableName).iterator();
				while(refIt.hasNext()){
					ReferenceInfo ref = refIt.next();
					sb.append(",").append(System.lineSeparator())
						.append("  CONSTRAINT ").append(ref.getConstraintName()).append(" FOREIGN KEY (").append(ref.getSrcColumnName()).append(") REFERENCES ")
						.append(ref.getRefSchema()).append(".").append(ref.getRefTableName()).append(" (").append(ref.getRefColumnName()).append(")");
				}
			}
			sb.append(System.lineSeparator()).append(");");
			log.info("create script for {} : {}", tableName, sb.toString());
			scripts.add(sb);
			if(dbModel.getIndexes().containsKey(tableName)){
				Map<String, IndexInfo> indexes = dbModel.getIndexes().get(tableName);
				Iterator<String> indNameIt = indexes.keySet().iterator();
				while(indNameIt.hasNext()){
					boolean withRowguid = false;
					String indName = indNameIt.next();
					StringBuilder indBuilder = new StringBuilder("CREATE ");
					IndexInfo indCols = indexes.get(indName);
					if(indCols.isUnique()){
						indBuilder.append("UNIQUE");
					}
					if("XML".equals(indCols.getIndexType())){
						indBuilder.append(" PRIMARY");
					}
					indBuilder.append(" ").append(indCols.getIndexType());
					indBuilder.append(" INDEX ").append(indCols.getIndexName()).append(" ON ");
					indBuilder.append(tableName);
					indBuilder.append(" (");
					Iterator<String> icIt = indCols.getColumns().iterator();
					while(icIt.hasNext()){
						String ic = icIt.next();
						if("rowguid".equals(ic)){
							withRowguid = true;
						}
						indBuilder.append(ic);
						if(icIt.hasNext()){
							indBuilder.append(", ");
						}
					}
					indBuilder.append(")");
					if(!indCols.getExtraColumns().isEmpty()){
						StringBuilder includeBuilder = new StringBuilder(" INCLUDE (");
						Iterator<String> xcIt = indCols.getExtraColumns().iterator();
						while(xcIt.hasNext()){
							String xc = xcIt.next();
							includeBuilder.append(xc);
							if(icIt.hasNext()){
								includeBuilder.append(", ");
							}
						}
						includeBuilder.append(")");
						indBuilder.append(includeBuilder.toString());
					}
					indBuilder.append(";");
					if(!withRowguid){
						log.info("index sql : {}", indBuilder);
						scripts.add(indBuilder);
					}
				};
			}
		}
		Set<String> sequences = new HashSet<>(sequences(dbModel.getSchema()));
		if(!dbModel.getSequences().isEmpty()){
			for(DbSequence seq : dbModel.getSequences()){
				if(sequences.contains(seq.qualifiedName())){
					continue;
				}
				StringBuilder seqBuilder = new StringBuilder("CREATE SEQUENCE ");
				seqBuilder.append(seq.getSchema()).append(".").append(seq.getName());
				seqBuilder.append(" START WITH ");
				if(seq.getLastValue() == null){
					seqBuilder.append(seq.getStartValue());
				}else{
					seqBuilder.append(seq.getLastValue() + 1L);
				}
				seqBuilder.append(" INCREMENT BY ").append(seq.getIncrementBy());
				if(seq.getMinValue() != null && seq.getMinValue() > Long.MIN_VALUE){
					seqBuilder.append(" MINVALUE ").append(seq.getMinValue());
				}else{
					seqBuilder.append(" NO MINVALUE");
				}
				if(seq.getMaxValue() != null && seq.getMaxValue() < Long.MAX_VALUE){
					seqBuilder.append(" MAXVALUE ").append(seq.getMaxValue());
				}else{
					seqBuilder.append(" NO MAXVALUE");
				}
				if(seq.isCycle()){
					seqBuilder.append(" CYCLE");
				}else{
					seqBuilder.append(" NO CYCLE");
				}
				if(seq.getCacheSize() != null && seq.getCacheSize() > 0L){
					seqBuilder.append(" CACHE ").append(seq.getCacheSize());
				}else{
					seqBuilder.append(" NO CACHE");
				}
				seqBuilder.append(";");

				log.info("sequence sql : {}", seqBuilder);
				scripts.add(seqBuilder);
			}
		}
		try{
			if(!checkSchema(dbModel.getSchema())){
				StringBuilder csSql = new StringBuilder("create schema ").append(dbModel.getSchema()).append(";");
				Statement css = conn.createStatement();
				css.execute(csSql.toString());
			}
			Statement s = conn.createStatement();
			for(StringBuilder sb : scripts){
				log.info("sql : {}", sb.toString());
				s.executeUpdate(sb.toString());
			}
		}catch(SQLException ignore){
			log.info("create tables sql", ignore);
		}
    }

	@Override
	public boolean checkSchema(String schema) throws SQLException{
		try(PreparedStatement ss = conn.prepareStatement(DatasourceFactorySqlserver.SQL_FOR_SCHEMA)){
			ss.setString(1, schema);
			ResultSet rss = ss.executeQuery();
			return rss.next();
		}
	}

    @Override
    public void close() throws Exception {
        if(conn != null){
            conn.close();
        }
    }
}
