package com.quemsi.model.flow.process;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbTable;

import lombok.AllArgsConstructor;
import lombok.Setter;

public class SchemaMapping extends AbstractStep{
    @Setter
    private String sourceSchema;
    @Setter
    private String targetSchema;

    @Override
    public void execute(FlowContext context) {
        context.getDbModelProcessors().add(new SchemaMappingProcessor(sourceSchema, targetSchema));
    }

    @AllArgsConstructor
    public class SchemaMappingProcessor implements DbModelProcessor{
        @Setter
        private String sourceSchema;
        @Setter
        private String targetSchema;
        @Override
        public void process(DbModel dbModel) {
            if(dbModel.getSchema().equalsIgnoreCase(sourceSchema)){
                dbModel.setSchema(targetSchema);
            }
            Map<String, DbTable> updatedTables = dbModel.getTables().values().stream().map( t-> {
                if(t.getSchema().equalsIgnoreCase(sourceSchema)){
                    t.setSchema(targetSchema);
                }
                t.getReferencedBy().stream().forEach(r -> {
                    if(r.getSchema().equalsIgnoreCase(sourceSchema)){
                        r.setSchema(targetSchema);
                    }
                });
                t.getReferences().stream().forEach(r -> {
                    if(r.getRefSchema().equalsIgnoreCase(sourceSchema)){
                        r.setRefSchema(targetSchema);
                    }
                });
                return t;
            }).collect(Collectors.toMap(t -> t.qualifiedName(), t -> t));
            dbModel.setTables(updatedTables);
            dbModel.getCircularIgnore().stream().filter(r -> r.getSrcSchema().equalsIgnoreCase(sourceSchema)).forEach(r -> {
                r.setSrcSchema(targetSchema);
            });
            dbModel.getReferenceInfos().stream().filter(r -> r.getSrcSchema().equalsIgnoreCase(sourceSchema)).forEach(r -> {
                r.setSrcSchema(targetSchema);
            });
            dbModel.getReferenceInfos().stream().filter(r -> r.getRefSchema().equalsIgnoreCase(sourceSchema)).forEach(r -> {
                r.setRefSchema(targetSchema);
            });
            dbModel.getSequences().stream().filter(s -> s.getSchema().equalsIgnoreCase(sourceSchema)).forEach(s -> {
                s.setSchema(targetSchema);
            });
            dbModel.getIndexes().values().stream().flatMap(i -> i.values().stream()).filter(i -> i.getSchemaName().equalsIgnoreCase(sourceSchema)).forEach(i ->{
                i.setSchemaName(targetSchema);
            });
        }
    }

    @Override
    public void fillDetails(List<Map<String, Object>> steps) {
        Map<String, Object> props = new HashMap<>();
        props.put("type", SchemaMapping.class.getSimpleName());
        props.put("sourceSchema", sourceSchema);
        props.put("targetSchema", targetSchema);
        steps.add(props);
    }
}
