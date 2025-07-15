package com.quemsi.model.flow.db.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.quemsi.commons.util.CommonOps;
import com.quemsi.model.flow.db.sql.DbModel.ReferencedColumn;
import com.quemsi.model.flow.db.sql.DbModel.TableReference;

import lombok.Getter;
import lombok.Setter;

public class DbTable {
    @Getter
    private String name;
    @Getter
    @Setter
    private Set<String> pkColumnNames;
    @Getter
    private Map<String, DbColumn> columns;
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
    public DbTable(String name){
        this();
        this.name = name;
    }

    public String joinedPkColumnNames(){
        return this.getPkColumnNames().stream().collect(Collectors.joining(", "));
    }
    public DbColumn[] orderedColumns(){
        ArrayList<DbColumn> list = new ArrayList<>(columns.values());
        Collections.sort(list, (c1, c2) -> c1.getOrdinalPosition().compareTo(c2.getOrdinalPosition()));
        return list.toArray(new DbColumn[list.size()]);
    }
    public DbColumn addColumn(String name, String dataType, Integer ordinalPosition, String columnType, Integer maxLength, Integer numPrecision, Integer numScale, String columnKey, String columnDefault, String nullable){
        DbColumn c = DbColumn.builder()
            .table(this)
            .name(name)
            .dataType(dataType)
            .ordinalPosition(ordinalPosition)
            .columnType(columnType)
            .maxLength(maxLength)
            .numPrecision(numPrecision)
            .numScale(numScale)
            .columnKey(columnKey)
            .columnDefault(columnDefault)
            .nullable(CommonOps.isTrue(nullable))
        .build();
        columns.put(name, c);
        return c;
    }
    public DbColumn addReference(DbColumn column, DbColumn referencedColumn, String contraintName){
        if(referencedColumn != null){
            column.setReferences(new ReferencedColumn(referencedColumn.getTable().getName(), referencedColumn.getName()));
            references.add(new TableReference(referencedColumn.getTable().getName()));
            referencedColumn.getTable().addReferencedBy(this);
        }
        return column;
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
    public void addReferencedBy(DbTable referencer){
        referencedBy.add(new TableReference(referencer.getName()));
    }
}
