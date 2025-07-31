package com.quemsi.model.flow.db.sql;

import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import com.quemsi.commons.util.Exceptions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
public class DbModel {
    private String format;
    private String sourceType;
    private String schema;
    protected Map<String, DbTable> tables;
    private List<ReferenceInfo> referenceInfos;
    protected Set<ReferenceInfo> circularIgnore;
    protected Map<String, Map<String, IndexInfo>> indexes;
    protected List<DbSequence> sequences;
    
    public DbModel(){
        tables = new HashMap<>();
        referenceInfos = new LinkedList<>();
        circularIgnore = new HashSet<>();
        indexes = new HashMap<>();
        sequences = new LinkedList<>();
    }

    public DbTable addTable(String tableName){
        DbTable table = new DbTable(tableName);
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
            DbColumn rColumn = rTable.findColumn(refInfo.getRefColumnName()).orElseThrow(Exceptions.server("unknow-column-in-fk")
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
        tables.values().stream().filter(t -> t.getReferences().size() == 0).forEach(t -> {
            queue.add(t);
        });
        while(!queue.isEmpty()){
            DbTable t = queue.pop();
            if(!processedIndex.contains(t)){
                if(t.getReferencedBy().size() > 0){
                    t.getReferencedBy().forEach(refTable -> queue.add(tables.get(refTable.getName())));
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
            if(t.getReferences().isEmpty()){
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
        private String indexType;
        private LinkedList<String> columns;
        public IndexInfo(String tableName, String indexName, boolean unique, String indexType){
            this.tableName = tableName;
            this.indexName = indexName;
            this.unique = unique;
            this.indexType = indexType;
            this.columns = new LinkedList<>();
        }
    }
}
