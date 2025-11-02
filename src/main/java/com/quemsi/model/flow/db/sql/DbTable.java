package com.quemsi.model.flow.db.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbModel.TableReference;
import com.quemsi.model.util.CommonHelpers;

import lombok.Getter;
import lombok.Setter;

public class DbTable {
    @Getter
    @Setter
    private String schema;
    @Getter
    private String name;
    @Getter
    @Setter
    private LinkedList<String> pkColumnNames;
    @Getter
    @Setter
    private String pkConstraintName;
    @Getter
    private Map<String, DbColumn> columns;
    @Getter
    private Set<TableReference> referencedBy;
    @Getter
    private Set<ReferenceInfo> references;

    public DbTable(){
        this.columns = new LinkedHashMap<>();
        this.referencedBy = new LinkedHashSet<>();
        this.references = new LinkedHashSet<>();
        pkColumnNames = new LinkedList<>();
    }
    public DbTable(String schema, String name){
        this();
        this.schema = schema;
        this.name = name;
    }

    public String joinedPkColumnNames(){
        return this.getPkColumnNames().stream().collect(Collectors.joining(", "));
    }
    public String qualifiedName(){
        return CommonHelpers.qualifiedName(schema, name);
    }
    public DbColumn[] orderedColumns(){
        ArrayList<DbColumn> list = new ArrayList<>(columns.values());
        Collections.sort(list, (c1, c2) -> c1.getOrdinalPosition().compareTo(c2.getOrdinalPosition()));
        return list.toArray(new DbColumn[list.size()]);
    }
    public DbColumn addColumn(DbColumn c){
        c.setTable(this);
        columns.put(c.getName(), c);
        return c;
    }
    public DbColumn column(String name){
        return columns.get(name);
    }
    public Optional<DbColumn> findColumn(String name){
        return Optional.ofNullable(columns.get(name));
    }
    public Set<String> columnNames(){
        return columns.keySet();
    }
    public void addReference(ReferenceInfo referenceInfo){
        references.add(referenceInfo);
    }
    public void addReferencedBy(DbTable referencer){
        referencedBy.add(new TableReference(referencer.getSchema(), referencer.getName()));
    }
}
