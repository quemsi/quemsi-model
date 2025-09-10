package com.quemsi.model.flow.db.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbTable;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class DDLServicePostgres implements DDLService{
    private Connection conn;

    @Override
    public boolean dropTables(String... tableNames) {
        try{
			Statement s = conn.createStatement();
			for(String tableName : tableNames){
				s.addBatch("DROP TABLE IF EXISTS " + tableName + ";");
			}
			s.executeBatch();
			return true;
		}catch(Exception e){
			e.printStackTrace();
			throw Exceptions.server("failed-to-clear-tables").withCause(e).get();
		}
    }

    @Override
    public void disableConstraints(Set<ReferenceInfo> constraints) {
        for(ReferenceInfo refInfo : constraints) {
            StringBuilder sb = new StringBuilder("ALTER TABLE ");
            sb.append(refInfo.getSrcTableName()).append(" DROP FOREIGN KEY ")
            .append(refInfo.getConstraintName()).append(";");
            try{
                String dropConstraintSql = sb.toString();
                log.info("drop constraint sql :{}", dropConstraintSql);
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
            sb.append(refInfo.getSrcTableName()).append(" ADD CONSTRAINT ")
            .append(refInfo.getConstraintName())
            .append(" FOREIGN KEY (").append(refInfo.getSrcColumnName())
            .append(") REFERENCES ").append(refInfo.getRefTableName()).append("(").append(refInfo.getRefColumnName()).append(");");
            try{
                String dropConstraintSql = sb.toString();
                log.info("drop constraint sql :{}", dropConstraintSql);
                Statement s = conn.createStatement();
                s.executeUpdate(dropConstraintSql);
            }catch(SQLException ignore){
                log.info("ignored enable constraint : " + refInfo.getConstraintName(), ignore);
            }
        }
    }
    private String columnType(String type, Integer maxLength){
        if("varchar".equals(type) && maxLength != null){
            return new StringBuffer(type).append("(").append(maxLength).append(")").toString();
        }
        return type;
    }
    @Override
    public void createTables(DbModel dbModel) {
        LinkedList<StringBuilder> scripts = new LinkedList<>();
        for(DbSequence seq : dbModel.getSequences()){
            StringBuilder seqStringBuilder = new StringBuilder("CREATE SEQUENCE IF NOT EXISTS ").append(seq.getName());
            if(seq.getIncrementBy() != null){
                seqStringBuilder.append(" INCREMENT BY ").append(seq.getIncrementBy());
            }
            if(seq.getMinValue() != null){
                seqStringBuilder.append(" MINVALUE ").append(seq.getMinValue());
            }else{
                seqStringBuilder.append(" NO MINVALUE ");
            }
            if(seq.getMaxValue() != null){
                seqStringBuilder.append(" MAXVALUE ").append(seq.getMaxValue());
            }else{
                seqStringBuilder.append(" NO MAXVALUE ");
            }
            if(seq.getStartValue() != null){
                seqStringBuilder.append(" START WITH ").append(seq.getStartValue());
            }
            if(seq.getCacheSize() != null){
                seqStringBuilder.append(" CACHE ").append(seq.getCacheSize());
            }
            if(!seq.isCycle()){
                seqStringBuilder.append(" NO");
            }
            seqStringBuilder.append(" CYCLE;");
            scripts.add(seqStringBuilder);
        }
		Map<String, List<ReferenceInfo>> tableReferences = dbModel.getReferenceInfos().stream().collect(Collectors.groupingBy(r -> new StringBuilder(r.getSrcSchema()).append(".").append(r.getSrcTableName()).toString()));
		for(String tableName : dbModel.orderedTableNames()){
			DbTable table = dbModel.findTable(tableName).orElseThrow();
			StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (").append(System.lineSeparator());
			DbColumn[] columns = table.orderedColumns();
			for(DbColumn c : columns){
				sb.append("  ").append(c.getName()).append(" ").append(columnType(c.getColumnType(), c.getMaxLength()));
				if(c.getColumnDefault() == null){
					if(c.isNullable() && !Set.of("TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT").contains(c.getColumnType().toUpperCase())){
						sb.append(" DEFAULT NULL");
					}
				}else{
					sb.append(" DEFAULT " + c.getColumnDefault().replaceAll("::regclass", ""));
				}
				if(!c.isNullable()){
					sb.append(" NOT NULL");
				}
				sb.append(",").append(System.lineSeparator());
			}
			if(table.getPkColumnNames().size() > 0){
                StringBuilder pkConst = new StringBuilder();
                Iterator<String> cIt = table.getPkColumnNames().iterator();
				String pkConstraintName = table.getPkColumnNames().stream().map(cn -> table.column(cn)).filter(c -> c.getConstraintName() != null).findFirst().map(c -> c.getConstraintName()).orElse(null);
                while(cIt.hasNext()){
					String cName = cIt.next();
					if(pkConst.length() == 0){
                        pkConst.append("  ").append("CONSTRAINT ");
                        if(pkConstraintName != null){
                            pkConst.append(pkConstraintName).append(" PRIMARY KEY (");
                        }
                    }
                    pkConst.append(cName);
					if(cIt.hasNext()){
						pkConst.append(", ");
					}
				}
				pkConst.append(")");
                sb.append(pkConst.toString());
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
					String indName = indNameIt.next();
					IndexInfo indCols = indexes.get(indName);
                    StringBuilder indBuilder = new StringBuilder("CREATE ");
                    if(indCols.isUnique()){
                        indBuilder.append("UNIQUE ");
                    }
                    indBuilder.append("INDEX ").append("IF NOT EXISTS ").append(indName);
                    indBuilder.append(" ON ").append(tableName).append(" USING ").append(indCols.getIndexType()).append(" (");
                    Iterator<String> icIt = indCols.getColumns().iterator();
                    while(icIt.hasNext()){
                        String ic = icIt.next();
                        indBuilder.append(ic);
                        if(icIt.hasNext()){
                            indBuilder.append(", ");
                        }
                    }
                    indBuilder.append(");");
                    log.info("create index {} for table {} : {}", indName, tableName, indBuilder);
                    scripts.add(indBuilder);
				};
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
                log.info("ddl : {}", sb.toString());
				s.executeUpdate(sb.toString());
			}
		}catch(SQLException ignore){
			log.info("create tables sql", ignore);
		}
    }

    @Override
	public boolean checkSchema(String schema) throws SQLException{
		try(PreparedStatement ss = conn.prepareStatement(DatasourceFactoryPostgres.SQL_FOR_SCHEMA)){
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
