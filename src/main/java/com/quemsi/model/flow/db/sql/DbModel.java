package com.quemsi.model.flow.db.sql;

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
    /** Software version of the agent that produced this backup. */
    private String agentVersion;
    /** Backup page size used when this model was produced. */
    private Integer batchSize;
    /** Backup parallelism used when this model was produced. */
    private Integer parallelism;
    private Set<String>schemas;
    protected Map<String, DbTable> tables;
    private List<ReferenceInfo> referenceInfos;
    private List<ContraintInfo> contraintInfos;
    private List<CheckConstraint> checkConstraints;
    protected Set<ReferenceInfo> circularIgnore;
    protected Map<String, Map<String, IndexInfo>> indexes;
    protected List<DbSequence> sequences;
    protected List<DbView> views;
    protected List<DbFunction> functions;
    /** PostgreSQL ENUM types (and similar) required before CREATE TABLE. */
    protected List<DbEnumType> enumTypes;
    /** PostgreSQL DOMAIN types required before CREATE TABLE. */
    protected List<DbDomainType> domainTypes;
    /** PostgreSQL triggers (created after data load). */
    protected List<DbTrigger> triggers;
    /** SQL Server full-text catalogs (created before FT indexes / CONTAINS routines). */
    protected List<DbFullTextCatalog> fullTextCatalogs;
    /** SQL Server full-text indexes (required before CONTAINS/FREETEXT views/routines). */
    protected List<DbFullTextIndex> fullTextIndexes;
    /** SQL Server XML schema collections (required before typed xml columns / XQuery views). */
    protected List<DbXmlSchemaCollection> xmlSchemaCollections;
    
    public DbModel(){
        tables = new HashMap<>();
        referenceInfos = new LinkedList<>();
        contraintInfos = new LinkedList<>();
        checkConstraints = new LinkedList<>();
        circularIgnore = new HashSet<>();
        indexes = new HashMap<>();
        sequences = new LinkedList<>();
        views = new LinkedList<>();
        functions = new LinkedList<>();
        enumTypes = new LinkedList<>();
        domainTypes = new LinkedList<>();
        triggers = new LinkedList<>();
        fullTextCatalogs = new LinkedList<>();
        fullTextIndexes = new LinkedList<>();
        xmlSchemaCollections = new LinkedList<>();
    }
    public DbTable addTable(String tableName){
        return addTable(tableName, null);
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
        return crateIfAbsent(tableName, null);
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
            // if(!StringUtils.equalsIgnoreCase(this.getSchema(), refInfo.getSrcSchema()) || !StringUtils.equalsIgnoreCase(this.getSchema(), refInfo.getRefSchema())){
            //     continue;
            // }
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
                .filter(r -> 
                    !t.qualifiedName().equals(r.refQualifiedName())
                    && !circularIgnore.contains(r)
                )
                .map(r -> index.contains(r.refQualifiedName())).reduce(Boolean.TRUE, (st, rs) -> st && rs);
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

    /**
     * Indexes for a table during restore/DDL.
     * Tries the map key first, then matches {@link IndexInfo} schema/table fields so backups
     * keyed by bare table name or (historically) {@code schema.indexName} still resolve.
     */
    public Map<String, IndexInfo> indexesForTable(String qualifiedTableName) {
        if (indexes == null || indexes.isEmpty() || qualifiedTableName == null) {
            return Map.of();
        }
        Map<String, IndexInfo> direct = indexes.get(qualifiedTableName);
        if (direct != null && !direct.isEmpty()) {
            return direct;
        }
        Map<String, IndexInfo> matched = new LinkedHashMap<>();
        for (Map<String, IndexInfo> byName : indexes.values()) {
            if (byName == null) {
                continue;
            }
            for (IndexInfo idx : byName.values()) {
                if (idx == null) {
                    continue;
                }
                String idxQualified = CommonHelpers.qualifiedName(idx.getSchemaName(), idx.getTableName());
                if (qualifiedTableName.equals(idxQualified) || qualifiedTableName.equals(idx.getTableName())) {
                    matched.putIfAbsent(idx.getIndexName(), idx);
                }
            }
        }
        if (!matched.isEmpty()) {
            return matched;
        }
        int dot = qualifiedTableName.indexOf('.');
        if (dot > 0) {
            String bare = qualifiedTableName.substring(dot + 1);
            direct = indexes.get(bare);
            if (direct != null && !direct.isEmpty()) {
                return direct;
            }
        }
        return matched;
    }

    /**
     * Topological order of views based on view-to-view dependencies.
     * Views that only depend on tables come first.
     */
    public LinkedList<DbView> orderedViews() {
        if (views == null || views.isEmpty()) {
            return new LinkedList<>();
        }
        Map<String, DbView> byName = views.stream()
            .collect(Collectors.toMap(DbView::qualifiedName, v -> v, (a, b) -> a));
        LinkedList<DbView> result = new LinkedList<>();
        Set<String> done = new LinkedHashSet<>();
        Deque<DbView> queue = new LinkedList<>(views);
        int guard = views.size() * views.size() + 1;
        while (!queue.isEmpty() && guard-- > 0) {
            DbView view = queue.poll();
            Set<String> deps = view.getDependsOnViews() == null ? Set.of() : view.getDependsOnViews();
            boolean depsReady = deps.stream()
                .filter(byName::containsKey)
                .allMatch(done::contains);
            if (depsReady) {
                if (done.add(view.qualifiedName())) {
                    result.add(view);
                }
            } else {
                queue.add(view);
            }
        }
        // Append any remaining (cycles / unresolved) in original order
        for (DbView view : views) {
            if (done.add(view.qualifiedName())) {
                result.add(view);
            }
        }
        return result;
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

    @Builder
    @Data
    @NoArgsConstructor
    public static class ContraintInfo {
        private String constraintName;
        private String schema;
        private String tableName;
        private LinkedList<String> columnNames;
        
        public ContraintInfo(
            String constraintName,
            String schema,
            String tableName,
            List<String> columnNames
        ) {
            this.constraintName = constraintName;
            this.schema = schema;
            this.tableName = tableName;
            this.columnNames = columnNames != null ? new LinkedList<>(columnNames) : new LinkedList<>();
        }
        
        public static class ContraintInfoBuilder {
            private LinkedList<String> columnNames = new LinkedList<>();
            
            public ContraintInfoBuilder columnName(String columnName) {
                if (this.columnNames == null) {
                    this.columnNames = new LinkedList<>();
                }
                this.columnNames.add(columnName);
                return this;
            }
            
            public ContraintInfoBuilder columnNames(java.util.Collection<? extends String> columnNames) {
                if (this.columnNames == null) {
                    this.columnNames = new LinkedList<>();
                }
                if (columnNames != null) {
                    this.columnNames.addAll(columnNames);
                }
                return this;
            }
            
            public ContraintInfo build() {
                return new ContraintInfo(
                    this.constraintName,
                    this.schema,
                    this.tableName,
                    this.columnNames != null ? new LinkedList<>(this.columnNames) : new LinkedList<>()
                );
            }
        }

        public String qualifiedConstraintName(){
            return CommonHelpers.qualifiedName(schema, constraintName);
        }
        public String qualifiedTableName(){
            return CommonHelpers.qualifiedName(schema, tableName);
        }

    }

    @Builder
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CheckConstraint {
        private String schema;
        private String tableName;
        private String constraintName;
        private String condef;

        public String qualifiedTableName(){
            return CommonHelpers.qualifiedName(schema, tableName);
        }

        public String qualifiedConstraintName(){
            return CommonHelpers.qualifiedName(schema, constraintName);
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
        /** Per-column index prefix lengths (MySQL SUB_PART); null entry means full column. */
        private LinkedList<Integer> columnPrefixLengths;
        private LinkedList<String> extraColumns;
        /**
         * SQL Server secondary XML index kind: PATH, VALUE, or PROPERTY.
         * Null for primary XML indexes and non-XML indexes.
         */
        private String xmlSecondaryType;
        /** SQL Server primary XML index name referenced by a secondary XML index. */
        private String usingXmlIndexName;
        public IndexInfo(String schemaName, String tableName, String indexName, boolean unique, String indexType){
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.indexName = indexName;
            this.unique = unique;
            this.indexType = indexType;
            this.columns = new LinkedList<>();
            this.columnPrefixLengths = new LinkedList<>();
            this.extraColumns = new LinkedList<>();
        }
        public String qualifiedTableName(){
            return CommonHelpers.qualifiedName(schemaName, tableName);
        }
        public boolean isXmlIndex() {
            return indexType != null && "XML".equalsIgnoreCase(indexType.trim());
        }
        public boolean isSecondaryXmlIndex() {
            return isXmlIndex() && xmlSecondaryType != null && !xmlSecondaryType.isBlank();
        }
    }
}
