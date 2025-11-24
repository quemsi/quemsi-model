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
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.util.CommonHelpers;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Singular;

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
        return addTable(tableName, this.schema);
    }
    public DbTable addTable(String tableName, String schema){
        DbTable table = new DbTable(schema, tableName);
        tables.put(table.qualifiedName(), table);
        return table;
    }
    public Optional<DbTable> findTable(String qualifiedName){
        return Optional.ofNullable(tables.get(qualifiedName));
    }
    public DbTable crateIfAbsent(String tableName){
        return crateIfAbsent(tableName, this.schema);
    }
    public DbTable crateIfAbsent(String tableName, String schema){
        Object qualifiedName = CommonHelpers.qualifiedName(schema, tableName);
        if(tables.containsKey(qualifiedName)){
            return tables.get(qualifiedName);
        }
        return addTable(tableName, schema);
    }

    public void build(){
        addReferenceInfosToColumns();
        findReferencesToBreakCycle();
    }

    public void addReferenceInfosToColumns(){
        for(ReferenceInfo refInfo : this.referenceInfos){
            if(!StringUtils.equalsIgnoreCase(this.getSchema(), refInfo.getSrcSchema()) || !StringUtils.equalsIgnoreCase(this.getSchema(), refInfo.getRefSchema())){
                continue;
            }
            DbTable sTable = this.findTable(refInfo.srcQualifiedName()).orElseThrow(Exceptions.server("invalid-src-table").withExtra("tableName", refInfo.getSrcTableName()).supplier());
            DbTable rTable = this.findTable(refInfo.refQualifiedName()).orElseThrow(Exceptions.server("unknow-table-in-fk")
                    .withExtra("schema", refInfo.getSrcSchema()).withExtra("tableName", refInfo.getSrcTableName()).withExtra("columnNames", refInfo.getSrcColumnNames()).withExtra("refSchema", refInfo.getRefSchema()).withExtra("refTable", refInfo.getRefTableName()).withExtra("refColumnNames", refInfo.getRefColumnNames()).supplier());
            for(String refColName : refInfo.getRefColumnNames()){
                rTable.findColumn(refColName).orElseThrow(Exceptions.server("unknow-column-in-fk").supplier());
            }
            sTable.addReference(refInfo);
            rTable.addReferencedBy(sTable);
        }
    }

    public void findReferencesToBreakCycle(){
        Map<String, Set<String>> reachablity = new HashMap<>();
        for(ReferenceInfo refInfo : referenceInfos){
            Queue<String> checkReachability = new LinkedList<>();
            checkReachability.add(refInfo.getSrcTableName());
            boolean reachableFrom = refInfo.getRefTableName().equals(refInfo.getSrcTableName());
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
                    val.add(refInfo.getSrcTableName());
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
                    t.getReferencedBy().forEach(refTable -> queue.add(tables.get(refTable.qualifiedName())));
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
                index.add(t.qualifiedName());
                result.add(t.qualifiedName());
            } else {
                boolean allProcessed = t.getReferences().stream()
                .filter(r -> !t.qualifiedName().equals(r.refQualifiedName())).map(r -> index.contains(r.refQualifiedName())).reduce(Boolean.TRUE, (st, rs) -> st && rs);
                if(allProcessed){
                    index.add(t.qualifiedName());
                    result.add(t.qualifiedName());
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
    public static class TableReference {
        private String schema;
        private String name;
        public String qualifiedName(){
            if(schema != null && !StringUtils.isEmptyOrNull(schema)){
                return new StringBuilder(schema).append(".").append(name).toString();
            }
            return name;
        }
    }

    @Builder
    @Data
    @NoArgsConstructor
    public static class ReferenceInfo {
        private String constraintName;
        private String srcSchema;
        private String srcTableName;
        @Singular
        private Set<String> srcColumnNames;
        private String refSchema;
        private String refTableName;
        @Singular
        private Set<String> refColumnNames;

        public ReferenceInfo(
            String constraintName,
            String srcSchema,
            String srcTableName,
            Set<String> srcColumnNames,
            String refSchema,
            String refTableName,
            Set<String> refColumnNames
        ) {
            this.constraintName = constraintName;
            this.srcSchema = srcSchema;
            this.srcTableName = srcTableName;
            this.srcColumnNames = new LinkedHashSet<>(srcColumnNames);
            this.refSchema = refSchema;
            this.refTableName = refTableName;
            this.refColumnNames = new LinkedHashSet<>(refColumnNames);
        }

        public String qualifiedConstraintName(){
            return CommonHelpers.qualifiedName(srcSchema, constraintName);
        }
        public String srcQualifiedName(){
            return CommonHelpers.qualifiedName(srcSchema, srcTableName);
        }
        public String refQualifiedName(){
            return CommonHelpers.qualifiedName(refSchema, refTableName);
        }

    }

    @NoArgsConstructor
    @Data
    public static class IndexInfo {
        private String schemaName;
        private String tableName;
        private String indexName;
        private boolean unique;
        private String indexType;
        private LinkedList<String> columns;
        private LinkedList<String> extraColumns;
        public IndexInfo(String schemaName, String tableName, String indexName, boolean unique, String indexType){
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.indexName = indexName;
            this.unique = unique;
            this.indexType = indexType;
            this.columns = new LinkedList<>();
            this.extraColumns = new LinkedList<>();
        }
        public String qualifiedTableName(){
            return new StringBuilder(this.schemaName).append(".").append(this.tableName).toString();
        }
    }
}
