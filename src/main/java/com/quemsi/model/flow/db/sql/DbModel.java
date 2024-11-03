package com.quemsi.model.flow.db.sql;

import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.quemsi.commons.util.Exceptions;

import lombok.Getter;

public class DbModel {
    protected Map<String, Table> tables;
    
    public DbModel(){
        tables = new HashMap<>();
    }

    public Table addTable(String tableName){
        Table table = new Table();
        table.name = tableName;
        tables.put(tableName, table);
        return table;
    }

    public Optional<Table> getTable(String tableName){
        return Optional.ofNullable(tables.get(tableName));
    }

    public Table crateIfAbsent(String tableName){
        if(tables.containsKey(tableName)){
            return tables.get(tableName);
        }
        return addTable(tableName);
    }

    public void controlCircularReference(LinkedList<String> chain, Table t){
        if(chain.contains(t.getName())){
            chain.addLast(t.getName());
            throw Exceptions.server("circular-reference").withExtra("chain", chain).get();
        }else{
            chain.addLast(t.getName());
            if(!t.getReferencedBy().isEmpty()){
                t.getReferencedBy().forEach(n -> controlCircularReference(new LinkedList<>(chain), n));
            }
        }
    }

    public Set<String> getOrderedTableNames(){
        tables.values().forEach(t -> controlCircularReference(new LinkedList<>(), t));;
        Set<String> s = new LinkedHashSet<>();
        Deque<Table> queue = new LinkedList<>();
        queue.addAll(tables.values());
        while(!queue.isEmpty()){
            Table t = queue.poll();
            if(t.referencedBy.isEmpty()){
                s.add(t.getName());
            } else {
                boolean allProcessed = t.getReferencedBy().stream().map(r -> s.contains(r.getName())).reduce(Boolean.TRUE, (st, rs) -> st && rs);
                if(allProcessed){
                    s.add(t.getName());
                }else{
                    queue.add(t);
                }
            }
        }
        return s;
    }

    public static class Table{
        @Getter
        private String name;
        @Getter
        private Map<String, Column> columns;
        @Getter
        private Set<Table> referencedBy;
        public Table(){
            this.columns = new LinkedHashMap<>();
            this.referencedBy = new LinkedHashSet<>();
        }
        public void addColumn(String name, String dataType, Column references, String constraintName){
            Column c = new Column();
            c.table = this;
            c.name = name;
            c.dataType = dataType;
            c.references = references;
            c.constraintName = constraintName;
            if(references != null){
                references.getTable().addReferencedBy(this);
            }
            columns.put(name, c);
        }
        public Optional<Column> getColumn(String name){
            return Optional.ofNullable(columns.get(name));
        }
        public void addReferencedBy(Table referencer){
            referencedBy.add(referencer);
        }
    }
    public static class Column {
        @Getter
        private Table table;
        @Getter
        private String name;
        @Getter
        private String dataType;
        @Getter
        private Column references;
        @Getter
        private String constraintName;
    }
}
