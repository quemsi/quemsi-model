package com.quemsi.model.flow.db.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.quemsi.commons.util.CommonOps;
import com.quemsi.commons.util.Exceptions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Data
public class DbModel {
    private String format;
    private String sourceType;
    protected Map<String, DbTable> tables;
    private List<ReferenceInfo> referenceInfos;
    protected Set<ReferenceInfo> circularIgnore;
    protected Map<String, Map<String, IndexInfo>> indexes;
    
    public DbModel(){
        tables = new HashMap<>();
        referenceInfos = new LinkedList<>();
        circularIgnore = new HashSet<>();
        indexes = new HashMap<>();
    }

    public DbTable addTable(String tableName){
        DbTable table = new DbTable();
        table.name = tableName;
        tables.put(tableName, table);
        return table;
    }

    public Optional<DbTable> findTable(String tableName){
        return Optional.ofNullable(tables.get(tableName));
    }

    public DbTable crateIfAbsent(String tableName){
        if(tables.containsKey(tableName)){
            return tables.get(tableName);
        }
        return addTable(tableName);
    }

    public void build(){
        addReferenceInfosToColumns();
        findReferencesToBreakCycle();
    }

    public void addReferenceInfosToColumns(){
        for(ReferenceInfo refInfo : this.referenceInfos){
            DbTable sTable = this.findTable(refInfo.getSrcTable()).orElseThrow(Exceptions.server("invalid-src-table").withExtra("tableName", refInfo.getSrcTable()).supplier());
            DbTable rTable = this.findTable(refInfo.getRefTableName()).orElseThrow(Exceptions.server("unknow-table-in-fk")
                    .withExtra("tableName", refInfo.getSrcTable()).withExtra("columnName", refInfo.getSrcColumnName()).withExtra("refTable", refInfo.getRefTableName()).withExtra("refColumn", refInfo.getRefColumnName()).supplier());
            Column rColumn = rTable.findColumn(refInfo.getRefColumnName()).orElseThrow(Exceptions.server("unknow-column-in-fk")
                .withExtra("refTable", refInfo.getRefTableName()).withExtra("refColumn", refInfo.getRefColumnName()).supplier());
            sTable.addReference(sTable.column(refInfo.getSrcColumnName()), rColumn, refInfo.getConstraintName());
        }
    }

    public void findReferencesToBreakCycle(){
        Map<String, Set<String>> reachablity = new HashMap<>();
        for(ReferenceInfo refInfo : referenceInfos){
            Queue<String> checkReachability = new LinkedList<>();
            checkReachability.add(refInfo.getSrcTable());
            boolean reachableFrom = refInfo.getRefTableName().equals(refInfo.getSrcTable());
            while(!reachableFrom && !checkReachability.isEmpty()){
                String target = checkReachability.poll();
                if(reachablity.containsKey(target)){
                    if(reachablity.get(target).contains(refInfo.getRefTableName())){
                        reachableFrom = true;
                    } else {
                        reachablity.get(target).forEach(checkReachability::add);
                    }
                }
            }
            if(reachableFrom){
                circularIgnore.add(refInfo);
            }else{
                reachablity.compute(refInfo.getRefTableName(), (key, val) -> {
                    if(val == null){
                        val = new HashSet<>();
                    }
                    val.add(refInfo.getSrcTable());
                    return val;
                });
            }
        }
    }

    public List<DbTable> referencesOrderedTables(){
        LinkedList<DbTable> list = new LinkedList<>();
        Set<DbTable> processedIndex = new HashSet<>();
        Deque<DbTable> queue = new LinkedList<>();
        tables.values().stream().filter(t -> t.references.size() == 0).forEach(t -> {
            queue.add(t);
        });
        while(!queue.isEmpty()){
            DbTable t = queue.pop();
            if(!processedIndex.contains(t)){
                if(t.referencedBy.size() > 0){
                    t.referencedBy.forEach(refTable -> queue.add(tables.get(refTable.getName())));
                }
                list.add(t);
                processedIndex.add(t);
            }
        }
        return list;
    }

    public LinkedList<String> orderedTableNames(){
        LinkedList<String> result = new LinkedList<>();
        Set<String> index = new LinkedHashSet<>();
        Deque<DbTable> queue = new LinkedList<>();
        queue.addAll(tables.values());
        while(!queue.isEmpty()){
            DbTable t = queue.poll();
            if(t.references.isEmpty()){
                index.add(t.getName());
                result.add(t.getName());
            } else {
                boolean allProcessed = t.getReferences().stream().filter(r -> !t.getName().equals(r.getName())).map(r -> index.contains(r.getName())).reduce(Boolean.TRUE, (st, rs) -> st && rs);
                if(allProcessed){
                    index.add(t.getName());
                    result.add(t.getName());
                }else{
                    queue.add(t);
                }
            }
        }
        return result;
    }
    public LinkedList<DbTable> orderedTables(){
        return this.orderedTableNames().stream().map(tName -> tables.get(tName)).collect(Collectors.toCollection(LinkedList::new));
    }
    
    public static class  DbTable{
        @Getter
        private String name;
        @Getter
        @Setter
        private Set<String> pkColumnNames;
        @Getter
        private Map<String, Column> columns;
        @Getter
        private Set<TableReference> referencedBy;
        @Getter
        private Set<TableReference> references;
        

        public DbTable(){
            this.columns = new LinkedHashMap<>();
            this.referencedBy = new LinkedHashSet<>();
            this.references = new LinkedHashSet<>();
            pkColumnNames = new HashSet<>();
        }
        public String joinedPkColumnNames(){
            return this.getPkColumnNames().stream().collect(Collectors.joining(", "));
        }
        public Column[] orderedColumns(){
            ArrayList<Column> list = new ArrayList<>(columns.values());
            Collections.sort(list, (c1, c2) -> c1.getOrdinalPosition().compareTo(c2.getOrdinalPosition()));
            return list.toArray(new Column[list.size()]);
        }
        public Column addColumn(String name, String dataType, Integer ordinalPosition, String columnType, Integer maxLength, Integer numPrecision, Integer numScale, String columnKey, String columnDefault, String nullable){
            Column c = new Column();
            c.table = this;
            c.name = name;
            c.dataType = dataType;
            c.ordinalPosition = ordinalPosition;
            c.columnType = columnType;
            c.maxLength = maxLength;
            c.numPrecision = numPrecision;
            c.numScale = numScale;
            c.columnKey = columnKey;
            c.columnDefault = columnDefault;
            c.nullable = CommonOps.isTrue(nullable);
            columns.put(name, c);
            return c;
        }
        public Column addReference(Column column, Column referencedColumn, String contraintName){
            if(referencedColumn != null){
                column.references = new ReferencedColumn(referencedColumn.getTable().getName(), referencedColumn.getName());
                references.add(new TableReference(referencedColumn.getTable().getName()));
                referencedColumn.getTable().addReferencedBy(this);
            }
            return column;
        }
        public Column column(String name){
            return columns.get(name);
        }
        public Optional<Column> findColumn(String name){
            return Optional.ofNullable(columns.get(name));
        }
        public Set<String> columnNames(){
            return columns.keySet();
        }
        public void addReferencedBy(DbTable referencer){
            referencedBy.add(new TableReference(referencer.getName()));
        }
    }
    public static class Column {
        @JsonIgnore
        @Getter
        private DbTable table;
        @Getter
        private String name;
        @Getter
        private String dataType;
        @Getter
        private ReferencedColumn references;
        @Getter
        private String constraintName;
        @Getter
        private Integer ordinalPosition;
        @Getter
        private Integer maxLength;
        @Getter
        private String columnType;
        @Getter
        private Integer numPrecision;
        @Getter
        private Integer numScale;
        @Getter
        private String columnKey;
        @Getter
        private String columnDefault;
        @Getter
        private boolean nullable;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReferencedColumn {
        private String on;
        private String column;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableReference {
        private String name;
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Data
    public static class ReferenceInfo {
        private String srcTable;
        private String srcColumnName;
        private String refTableName;
        private String refColumnName;
        private String constraintName;
    }

    @NoArgsConstructor
    @Data
    public static class IndexInfo {
        private String tableName;
        private String indexName;
        private boolean unique;
        private LinkedList<String> columns;
        public IndexInfo(String tableName, String indexName, boolean unique){
            this.tableName = tableName;
            this.indexName = indexName;
            this.unique = unique;
            this.columns = new LinkedList<>();
        }
    }
}
