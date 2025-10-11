package com.quemsi.model.flow.process;

import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.db.sql.DbModel;

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
            dbModel.getTables().values().stream().filter(t -> t.getSchema().equalsIgnoreCase(sourceSchema)).forEach(t -> {
                t.setSchema(targetSchema);
            });
        }
    }
}
