package com.quemsi.model.flow.process;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.quemsi.commons.util.StringUtils;
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
            Set<String> schemas = dbModel.getSchemas();
            if(schemas == null || schemas.isEmpty()){
                return;
            }
            
            // Find the actual schema string in the set (case-insensitive match)
            Optional<String> matchingSchema = schemas.stream()
                .filter(s -> StringUtils.equalsIgnoreCase(s, sourceSchema))
                .findFirst();
            
            // Early return if sourceSchema doesn't exist
            if(!matchingSchema.isPresent()){
                return;
            }
            
            // Remove the matching schema and add targetSchema if not already present
            String actualSchema = matchingSchema.get();
            schemas.remove(actualSchema);
            if(!schemas.stream().anyMatch(s -> StringUtils.equalsIgnoreCase(s, targetSchema))){
                schemas.add(targetSchema);
            }
            
            // Update all schema references
            Map<String, DbTable> updatedTables = dbModel.getTables().values().stream().map( t-> {
                if(StringUtils.equalsIgnoreCase(t.getSchema(), sourceSchema)){
                    t.setSchema(targetSchema);
                }
                t.getReferencedBy().stream().forEach(r -> {
                    if(StringUtils.equalsIgnoreCase(r.getSchema(), sourceSchema)){
                        r.setSchema(targetSchema);
                    }
                });
                t.getReferences().stream().forEach(r -> {
                    if(StringUtils.equalsIgnoreCase(r.getRefSchema(), sourceSchema)){
                        r.setRefSchema(targetSchema);
                    }
                });
                return t;
            }).collect(Collectors.toMap(t -> t.qualifiedName(), t -> t));
            dbModel.setTables(updatedTables);
            
            dbModel.getCircularIgnore().stream().filter(r -> StringUtils.equalsIgnoreCase(r.getSrcSchema(), sourceSchema)).forEach(r -> {
                r.setSrcSchema(targetSchema);
            });
            
            dbModel.getReferenceInfos().stream().filter(r -> StringUtils.equalsIgnoreCase(r.getSrcSchema(), sourceSchema)).forEach(r -> {
                r.setSrcSchema(targetSchema);
            });
            
            dbModel.getReferenceInfos().stream().filter(r -> StringUtils.equalsIgnoreCase(r.getRefSchema(), sourceSchema)).forEach(r -> {
                r.setRefSchema(targetSchema);
            });
            
            dbModel.getContraintInfos().stream().filter(c -> StringUtils.equalsIgnoreCase(c.getSchema(), sourceSchema)).forEach(c -> {
                c.setSchema(targetSchema);
            });
            
            dbModel.getCheckConstraints().stream().filter(c -> StringUtils.equalsIgnoreCase(c.getSchema(), sourceSchema)).forEach(c -> {
                c.setSchema(targetSchema);
            });
            
            dbModel.getSequences().stream().filter(s -> StringUtils.equalsIgnoreCase(s.getSchema(), sourceSchema)).forEach(s -> {
                s.setSchema(targetSchema);
            });
            
            if (dbModel.getViews() != null) {
                dbModel.getViews().stream().filter(v -> StringUtils.equalsIgnoreCase(v.getSchema(), sourceSchema)).forEach(v -> {
                    v.setSchema(targetSchema);
                    if (v.getDefinition() != null) {
                        v.setDefinition(remapSchemaInDefinition(v.getDefinition(), sourceSchema, targetSchema));
                    }
                    if (v.getDependsOnViews() != null && !v.getDependsOnViews().isEmpty()) {
                        v.setDependsOnViews(v.getDependsOnViews().stream()
                            .map(dep -> remapQualifiedName(dep, sourceSchema, targetSchema))
                            .collect(Collectors.toSet()));
                    }
                });
            }
            
            dbModel.getIndexes().values().stream().flatMap(i -> i.values().stream()).filter(i -> StringUtils.equalsIgnoreCase(i.getSchemaName(), sourceSchema)).forEach(i ->{
                i.setSchemaName(targetSchema);
            });
        }

        private String remapQualifiedName(String qualifiedName, String source, String target) {
            if (qualifiedName == null) {
                return null;
            }
            String prefix = source + ".";
            if (qualifiedName.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return target + "." + qualifiedName.substring(prefix.length());
            }
            return qualifiedName;
        }

        private String remapSchemaInDefinition(String definition, String source, String target) {
            String result = definition;
            result = result.replace("\"" + source + "\".", "\"" + target + "\".");
            result = result.replace("[" + source + "].", "[" + target + "].");
            // Unquoted schema.table — word-boundary-ish replace of source.
            result = result.replaceAll("(?i)(?<![\\w\"`])" + java.util.regex.Pattern.quote(source) + "\\.", target + ".");
            return result;
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
