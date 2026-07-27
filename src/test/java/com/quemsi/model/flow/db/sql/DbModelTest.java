package com.quemsi.model.flow.db.sql;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.model.flow.TableDataObjectMapper;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;

public class DbModelTest {
    @Test
    public void testGivenTwoDistinctTablesWhenBuildThenShouldReflectStructure(){
        DbModel dbModel = new DbModel();
        DbTable t1 =  dbModel.crateIfAbsent("T1");
        t1.addColumn(DbColumn.builder().name("c1").dataType("varchar2(255)").build());
        t1.addColumn(DbColumn.builder().name("c2").dataType("bigint").build());

        DbTable t2 = dbModel.crateIfAbsent("T2");
        t2.addColumn(DbColumn.builder().name("ac1").dataType("int").build());
        t2.addColumn(DbColumn.builder().name("ac2").dataType("tinyint").build());
        t2.addColumn(DbColumn.builder().name("ac3").dataType("text").build());

        assertThat(t1.getColumns().size(), equalTo(2));
        assertThat(t2.getColumns().size(), equalTo(3));
        assertThat(t1.getReferencedBy().size(), equalTo(0));
        assertThat(t1.getReferences().size(), equalTo(0));
    }

    @Test
    public void testGivenTwoTablesWithSingleForeignKeyWhenBuildThenShouldReflectStructure(){
        DbModel dbModel = new DbModel();
        DbTable t1 =  dbModel.crateIfAbsent("T1");
        t1.addColumn(DbColumn.builder().name("c1").dataType("varchar2(255)").build());
        DbColumn t1c2 = t1.addColumn(DbColumn.builder().name("c2").dataType("bigint").build());

        DbTable t2 = dbModel.crateIfAbsent("T2");
        t2.addColumn(DbColumn.builder().name("ac1").dataType("int").build());
        t2.addColumn(DbColumn.builder().name("ac2").dataType("tinyint").build());
        DbColumn t2ac3 = t2.addColumn(DbColumn.builder().name("ac3").dataType("bigint").build());
        dbModel.setReferenceInfos(List.of(ReferenceInfo.builder().constraintName("fk_t2_t1_c2").srcTableName(t2.getName()).srcColumnName(t2ac3.getName()).refTableName(t1.getName()).refColumnName(t1c2.getName()).build()));

        dbModel.build();

        assertThat(t1.getColumns().size(), equalTo(2));
        assertThat(t2.getColumns().size(), equalTo(3));
        assertThat(t1.getReferencedBy().size(), equalTo(1));
        assertThat(t1.getReferences().size(), equalTo(0));
        assertThat(t2.getReferencedBy().size(), equalTo(0));
        assertThat(t2.getReferences().size(), equalTo(1));
    }

    @Test
    public void testGivenTreeTablesWithSingleForeignKeysWhenGetOrderedThenShouldReturnNotReferencedTablesFirst(){
        DbModel dbModel = new DbModel();
        DbTable t1 =  dbModel.crateIfAbsent("T1");
        t1.addColumn(DbColumn.builder().name("c1").dataType("varchar2(255)").build());
        DbColumn t1c2 = t1.addColumn(DbColumn.builder().name("c2").dataType("bigint").build());

        DbTable t2 = dbModel.crateIfAbsent("T2");
        t2.addColumn(DbColumn.builder().name("ac1").dataType("int").build());
        DbColumn t2ac2 = t2.addColumn(DbColumn.builder().name("ac2").dataType("tinyint").build());
        
        DbTable t3 = dbModel.crateIfAbsent("T3");
        t3.addColumn(DbColumn.builder().name("bc1").dataType("int").build());
        DbColumn t3bc2 = t3.addColumn(DbColumn.builder().name("bc2").dataType("tinyint").build());
        t3.addColumn(DbColumn.builder().name("bc3").dataType("bigint").build());

        dbModel.setReferenceInfos( List.of(
            ReferenceInfo.builder().constraintName("fk_t2_t1_c2").srcTableName(t2.getName()).srcColumnName(t2ac2.getName()).refTableName(t1.getName()).refColumnName(t1c2.getName()).build()
            , ReferenceInfo.builder().constraintName("fk_t3_t2_ac2").srcTableName(t3.getName()).srcColumnName(t3bc2.getName()).refTableName(t2.getName()).refColumnName(t2ac2.getName()).build()
        ));

        dbModel.build();

        assertThat(dbModel.referencesOrderedTables(), contains(
            hasProperty("name", equalTo("T1"))
            , hasProperty("name", equalTo("T2"))
            , hasProperty("name", equalTo("T3"))
        ));
    }

    @Test
    public void testGivenMultipTablesWithManyNonCyclicRelationsWhenGetSortedTableListThenShouldReturnNotReferencedTablesFirst(){
        DbModel dbModel = new DbModel();
        DbTable r1 =  dbModel.crateIfAbsent("R1");
        DbColumn r1Id = r1.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        
        DbTable r2 =  dbModel.crateIfAbsent("R2");
        DbColumn r2Id = r2.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        
        DbTable r3 =  dbModel.crateIfAbsent("R3");
        DbColumn r3Id = r3.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn r3r1Id = r3.addColumn(DbColumn.builder().name("r1_id").dataType("bigint").build());

        
        DbTable r4 =  dbModel.crateIfAbsent("R4");
        DbColumn r4Id = r4.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        
        DbTable f1 = dbModel.crateIfAbsent("F1");
        DbColumn f1Id = f1.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn f1r1Id = f1.addColumn(DbColumn.builder().name("r1_id").dataType("bigint").build());
        DbColumn f1r2Id = f1.addColumn(DbColumn.builder().name("r2_id").dataType("bigint").build());
        
        DbTable f2 = dbModel.crateIfAbsent("F2");
        f2.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn f2f1Id = f2.addColumn(DbColumn.builder().name("f1_id").dataType("bigint").build());
        
        DbTable f3 = dbModel.crateIfAbsent("F3");
        f3.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn f3r1Id = f3.addColumn(DbColumn.builder().name("r1_id").dataType("bigint").build());
        DbColumn f3r2Id = f3.addColumn(DbColumn.builder().name("r2_id").dataType("bigint").build());
        DbColumn f3r3Id = f3.addColumn(DbColumn.builder().name("r3_id").dataType("bigint").build());
        DbColumn f3r4Id = f3.addColumn(DbColumn.builder().name("r4_id").dataType("bigint").build());
        
        DbTable f4 =  dbModel.crateIfAbsent("F4");
        f4.addColumn(DbColumn.builder().name("id").dataType("bigint").build());


        dbModel.setReferenceInfos(List.of(
        ReferenceInfo.builder().constraintName("fk_r3_r1").srcTableName(r3.getName()).srcColumnName(r3r1Id.getName()).refTableName(r1.getName()).refColumnName(r1Id.getName()).build(),
        ReferenceInfo.builder().constraintName("fk_f1_r1").srcTableName(f1.getName()).srcColumnName(f1r1Id.getName()).refTableName(r1.getName()).refColumnName(r1Id.getName()).build(),
        ReferenceInfo.builder().constraintName("fk_f1_r2").srcTableName(f1.getName()).srcColumnName(f1r2Id.getName()).refTableName(r2.getName()).refColumnName(r2Id.getName()).build(),
        ReferenceInfo.builder().constraintName("fk_f2_f1").srcTableName(f2.getName()).srcColumnName(f2f1Id.getName()).refTableName(f1.getName()).refColumnName(f1Id.getName()).build(),
        ReferenceInfo.builder().constraintName("fk_f2_r3").srcTableName(f2.getName()).srcColumnName(f3r3Id.getName()).refTableName(r3.getName()).refColumnName(r3Id.getName()).build(),
        ReferenceInfo.builder().constraintName("fk_f3_r1").srcTableName(f3.getName()).srcColumnName(f3r1Id.getName()).refTableName(r1.getName()).refColumnName(r1Id.getName()).build(),
        ReferenceInfo.builder().constraintName("fk_f3_r2").srcTableName(f3.getName()).srcColumnName(f3r2Id.getName()).refTableName(r2.getName()).refColumnName(r2Id.getName()).build(),
        ReferenceInfo.builder().constraintName("fk_f3_r3").srcTableName(f3.getName()).srcColumnName(f3r3Id.getName()).refTableName(r3.getName()).refColumnName(r3Id.getName()).build(),
        ReferenceInfo.builder().constraintName("fk_f3_r4").srcTableName(f3.getName()).srcColumnName(f3r4Id.getName()).refTableName(r4.getName()).refColumnName(r4Id.getName()).build()));


        dbModel.build();

        List<DbTable> sorted = dbModel.orderedTables();
        assertThat(sorted.size(), equalTo(8));
        int refCount = 0;
        for(DbTable t : sorted){
            if(refCount > t.getReferences().size()){
                fail("smaller refcount later on");
            }else{
                refCount = t.getReferences().size();
            }
        }
    }

    @Test
    public void givenTwoTablesOneRefWithoutCircleReferenceWhenBuildThenShouldReturnEmptyIgnoreList(){
        DbModel dbModel = new DbModel();
        DbTable r1 =  dbModel.crateIfAbsent("R1");
        DbColumn r1Id = r1.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        
        DbTable r2 =  dbModel.crateIfAbsent("R2");
        r2.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn r2r1Id = r2.addColumn(DbColumn.builder().name("r2r1Id").dataType("bigint").build());
        
        ReferenceInfo refInfo = ReferenceInfo.builder().srcTableName(r2.getName()).srcColumnName(r2r1Id.getName()).refTableName(r1.getName()).refColumnName(r1Id.getName()).build();
        dbModel.setReferenceInfos(List.of(refInfo));

        dbModel.build();

        assertThat(r1.getReferencedBy().size(), equalTo(1));
        assertThat(r2.getReferences().size(), equalTo(1));
        assertThat(dbModel.getCircularIgnore(), empty());
    }

    @Test
    public void givenTreeTablesTreeRefsWithoutCircleReferenceWhenBuildThenShouldReturnEmptyIgnoreList(){
        DbModel dbModel = new DbModel();
        DbTable r1 =  dbModel.crateIfAbsent("R1");
        DbColumn r1Id = r1.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn r1r2Id = r1.addColumn(DbColumn.builder().name("r2Id").dataType("bigint").build());
        
        DbTable r2 =  dbModel.crateIfAbsent("R2");
        DbColumn r2Id = r2.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        
        DbTable r3 =  dbModel.crateIfAbsent("R3");
        r3.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn r3r1Id = r3.addColumn(DbColumn.builder().name("r1Id").dataType("bigint").build());
        DbColumn r3r2Id = r3.addColumn(DbColumn.builder().name("r2Id").dataType("bigint").build());
        

        dbModel.setReferenceInfos(List.of(
            ReferenceInfo.builder().srcTableName(r1.getName()).srcColumnName(r1r2Id.getName()).refTableName(r2.getName()).refColumnName(r2Id.getName()).build(),
            ReferenceInfo.builder().srcTableName(r3.getName()).srcColumnName(r3r1Id.getName()).refTableName(r1.getName()).refColumnName(r1Id.getName()).build(),
            ReferenceInfo.builder().srcTableName(r3.getName()).srcColumnName(r3r2Id.getName()).refTableName(r2.getName()).refColumnName(r2Id.getName()).build()
        ));

        dbModel.build();

        assertThat(r1.getReferencedBy().size(), equalTo(1));
        assertThat(r2.getReferencedBy().size(), equalTo(2));
        assertThat(dbModel.getCircularIgnore(), empty());
    }

    @Test
    public void givenTreeTablesTreeRefsWithCircleReferenceWhenBuildThenShouldReturnNonEmptyIgnoreList(){
        DbModel dbModel = new DbModel();
        DbTable r1 =  dbModel.crateIfAbsent("R1");
        DbColumn r1Id = r1.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn r1r2Id = r1.addColumn(DbColumn.builder().name("r2Id").dataType("bigint").build());
        
        DbTable r2 =  dbModel.crateIfAbsent("R2");
        DbColumn r2Id = r2.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn r2r3Id = r2.addColumn(DbColumn.builder().name("r3Id").dataType("bigint").build());
        
        DbTable r3 =  dbModel.crateIfAbsent("R3");
        DbColumn r3Id = r3.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn r3r1Id = r3.addColumn(DbColumn.builder().name("r1Id").dataType("bigint").build());
        

        dbModel.setReferenceInfos(List.of(
            ReferenceInfo.builder().srcTableName(r1.getName()).srcColumnName(r1r2Id.getName()).refTableName(r2.getName()).refColumnName(r2Id.getName()).build(),
            ReferenceInfo.builder().srcTableName(r2.getName()).srcColumnName(r2r3Id.getName()).refTableName(r3.getName()).refColumnName(r3Id.getName()).build(),
            ReferenceInfo.builder().srcTableName(r3.getName()).srcColumnName(r3r1Id.getName()).refTableName(r1.getName()).refColumnName(r1Id.getName()).build()
        ));

        dbModel.build();

        assertThat(r1.getReferencedBy().size(), equalTo(1));
        assertThat(r2.getReferencedBy().size(), equalTo(1));
        assertThat(r3.getReferencedBy().size(), equalTo(1));
        assertThat(dbModel.getCircularIgnore().size(), equalTo(1));
    }

    @Test
    public void givenTreeTablesTreeRefsWithSelfReferenceWhenBuildThenShouldReturnIgnoreList(){
        DbModel dbModel = new DbModel();
        DbTable r1 =  dbModel.crateIfAbsent("R1");
        r1.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn r1r2Id = r1.addColumn(DbColumn.builder().name("r2Id").dataType("bigint").build());
        
        DbTable r2 =  dbModel.crateIfAbsent("R2");
        DbColumn r2Id = r2.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn r2r3Id = r2.addColumn(DbColumn.builder().name("r3Id").dataType("bigint").build());
        
        DbTable r3 =  dbModel.crateIfAbsent("R3");
        DbColumn r3Id = r3.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn r3r3Id = r3.addColumn(DbColumn.builder().name("r3Id").dataType("bigint").build());
        

        dbModel.getReferenceInfos().addAll(List.of(
            ReferenceInfo.builder().srcTableName(r1.getName()).srcColumnName(r1r2Id.getName()).refTableName(r2.getName()).refColumnName(r2Id.getName()).build(),
            ReferenceInfo.builder().srcTableName(r2.getName()).srcColumnName(r2r3Id.getName()).refTableName(r3.getName()).refColumnName(r3Id.getName()).build(),
            ReferenceInfo.builder().srcTableName(r3.getName()).srcColumnName(r3r3Id.getName()).refTableName(r3.getName()).refColumnName(r3Id.getName()).build()
        ));

        dbModel.build();

        assertThat(r1.getReferencedBy().size(), equalTo(0));
        assertThat(r2.getReferencedBy().size(), equalTo(1));
        assertThat(r3.getReferencedBy().size(), equalTo(2));
        assertThat(dbModel.getCircularIgnore().size(), equalTo(1));
    }


    @Test
    public void givenComplexModelWithTwoCyclesWhenBuildThenShouldReturnIgnoreList(){
        DbModel dbModel = new DbModel();
        DbTable r1 =  dbModel.crateIfAbsent("R1");
        DbColumn r1Id = r1.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn r1f3Id = r1.addColumn(DbColumn.builder().name("f3Id").dataType("bigint").build());
        
        DbTable f1 =  dbModel.crateIfAbsent("F1");
        DbColumn f1Id = f1.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn f1r1Id = f1.addColumn(DbColumn.builder().name("r1Id").dataType("bigint").build());
        DbColumn f1r2Id = f1.addColumn(DbColumn.builder().name("r2Id").dataType("bigint").build());
        
        DbTable r2 =  dbModel.crateIfAbsent("R2");
        DbColumn r2Id = r2.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn r2r4Id = r2.addColumn(DbColumn.builder().name("r4Id").dataType("bigint").build());

        DbTable f2 =  dbModel.crateIfAbsent("F2");
        f2.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn f2f1Id = f2.addColumn(DbColumn.builder().name("f1Id").dataType("bigint").build());
        DbColumn f2r3Id = f2.addColumn(DbColumn.builder().name("r3Id").dataType("bigint").build());

        DbTable r3 =  dbModel.crateIfAbsent("R3");
        DbColumn r3Id = r3.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn r3r1Id = r3.addColumn(DbColumn.builder().name("r1Id").dataType("bigint").build());

        DbTable f3 =  dbModel.crateIfAbsent("F3");
        DbColumn f3Id = f3.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn f3r3Id = f3.addColumn(DbColumn.builder().name("r3Id").dataType("bigint").build());
        DbColumn f3r2Id = f3.addColumn(DbColumn.builder().name("r2Id").dataType("bigint").build());

        DbTable r4 =  dbModel.crateIfAbsent("R4");
        DbColumn r4Id = r4.addColumn(DbColumn.builder().name("id").dataType("bigint").build());
        DbColumn r4f3Id = r4.addColumn(DbColumn.builder().name("f3Id").dataType("bigint").build());

        dbModel.crateIfAbsent("f4");
        
        

        dbModel.getReferenceInfos().addAll(List.of(
            ReferenceInfo.builder().srcTableName(r1.getName()).srcColumnName(r1f3Id.getName()).refTableName(f3.getName()).refColumnName(f3Id.getName()).build(),
            ReferenceInfo.builder().srcTableName(f1.getName()).srcColumnName(f1r1Id.getName()).refTableName(r1.getName()).refColumnName(r1Id.getName()).build(),
            ReferenceInfo.builder().srcTableName(f1.getName()).srcColumnName(f1r2Id.getName()).refTableName(r2.getName()).refColumnName(r2Id.getName()).build(),
            ReferenceInfo.builder().srcTableName(f2.getName()).srcColumnName(f2f1Id.getName()).refTableName(f1.getName()).refColumnName(f1Id.getName()).build(),
            ReferenceInfo.builder().srcTableName(f2.getName()).srcColumnName(f2r3Id.getName()).refTableName(r3.getName()).refColumnName(r3Id.getName()).build(),
            ReferenceInfo.builder().srcTableName(r2.getName()).srcColumnName(r2r4Id.getName()).refTableName(r4.getName()).refColumnName(r4Id.getName()).build(),
            ReferenceInfo.builder().srcTableName(r3.getName()).srcColumnName(r3r1Id.getName()).refTableName(r1.getName()).refColumnName(r1Id.getName()).build(),
            ReferenceInfo.builder().srcTableName(f3.getName()).srcColumnName(f3r3Id.getName()).refTableName(r3.getName()).refColumnName(r3Id.getName()).build(),
            ReferenceInfo.builder().srcTableName(f3.getName()).srcColumnName(f3r2Id.getName()).refTableName(r2.getName()).refColumnName(r2Id.getName()).build(),
            ReferenceInfo.builder().srcTableName(r4.getName()).srcColumnName(r4f3Id.getName()).refTableName(f3.getName()).refColumnName(f3Id.getName()).build()
        ));

        dbModel.build();

        assertThat(dbModel.getCircularIgnore().size(), equalTo(2));
    }

    @Test
    public void givenHrEmployeesDepartmentsCycleWhenOrderedThenEmployeesBeforeDepartments(){
        DbModel dbModel = hrModelWithCycle();

        dbModel.build();

        assertThat(dbModel.getCircularIgnore().isEmpty(), equalTo(false));
        assertThat(dbModel.getCircularIgnore().stream().anyMatch(r -> "EMP_MANAGER_FK".equals(r.getConstraintName())), equalTo(true));

        List<String> ordered = dbModel.orderedTableNames();
        assertThat(ordered.contains("HR.EMPLOYEES"), equalTo(true));
        assertThat(ordered.contains("HR.DEPARTMENTS"), equalTo(true));
        assertThat(ordered.indexOf("HR.JOBS") < ordered.indexOf("HR.EMPLOYEES"), equalTo(true));
        assertThat(ordered.indexOf("HR.LOCATIONS") < ordered.indexOf("HR.DEPARTMENTS"), equalTo(true));
        assertThat(ordered.indexOf("HR.DEPARTMENTS") < ordered.indexOf("HR.EMPLOYEES"), equalTo(true));
    }

    @Test
    public void build_isIdempotent_andDoesNotDuplicateReferences() {
        DbModel dbModel = hrModelWithCycle();
        dbModel.build();
        int empRefs = dbModel.findTable("HR.EMPLOYEES").orElseThrow().getReferences().size();
        int circular = dbModel.getCircularIgnore().size();

        dbModel.build();

        assertThat(dbModel.findTable("HR.EMPLOYEES").orElseThrow().getReferences().size(), equalTo(empRefs));
        assertThat(dbModel.getCircularIgnore().size(), equalTo(circular));
        assertThat(dbModel.orderedTableNames().indexOf("HR.DEPARTMENTS")
            < dbModel.orderedTableNames().indexOf("HR.EMPLOYEES"), equalTo(true));
    }

    @Test
    public void afterJsonRoundTrip_buildRestoresTableReferencesForRestoreOrdering() throws Exception {
        DbModel original = hrModelWithCycle();
        original.build();
        ObjectMapper om = TableDataObjectMapper.create();
        String json = om.writeValueAsString(original);

        DbModel restored = om.readValue(json, DbModel.class);
        // Re-build so circularIgnore / graph match referenceInfos even if JSON shape drifts.
        restored.build();

        assertThat(restored.findTable("HR.EMPLOYEES").orElseThrow().getReferences().isEmpty(), equalTo(false));
        assertThat(restored.orderedTableNames().indexOf("HR.DEPARTMENTS")
            < restored.orderedTableNames().indexOf("HR.EMPLOYEES"), equalTo(true));
    }

    @Test
    public void whenDeptMgrFkDiscoveredBeforeEmpDeptFk_empDeptMayBeCircularIgnored() {
        // Oracle ALL_CONSTRAINTS order is by table name, so DEPT_* is seen before EMP_*.
        // That puts EMP_DEPT_FK into circularIgnore — restore must drop FKs before load
        // (RdbmsTarget.disableConstraints) or EMPLOYEES can insert before DEPARTMENTS.
        DbModel dbModel = hrModelWithCycle();
        List<ReferenceInfo> oracleLikeOrder = List.of(
            dbModel.getReferenceInfos().stream().filter(r -> "DEPT_LOC_FK".equals(r.getConstraintName())).findFirst().orElseThrow(),
            dbModel.getReferenceInfos().stream().filter(r -> "DEPT_MGR_FK".equals(r.getConstraintName())).findFirst().orElseThrow(),
            dbModel.getReferenceInfos().stream().filter(r -> "EMP_DEPT_FK".equals(r.getConstraintName())).findFirst().orElseThrow(),
            dbModel.getReferenceInfos().stream().filter(r -> "EMP_JOB_FK".equals(r.getConstraintName())).findFirst().orElseThrow(),
            dbModel.getReferenceInfos().stream().filter(r -> "EMP_MANAGER_FK".equals(r.getConstraintName())).findFirst().orElseThrow()
        );
        dbModel.setReferenceInfos(oracleLikeOrder);

        dbModel.build();

        assertThat(dbModel.getCircularIgnore().stream().anyMatch(r -> "EMP_DEPT_FK".equals(r.getConstraintName())),
            equalTo(true));
    }

    private static DbModel hrModelWithCycle() {
        DbModel dbModel = new DbModel();

        DbTable jobs = dbModel.crateIfAbsent("JOBS", "HR");
        DbColumn jobId = jobs.addColumn(DbColumn.builder().name("JOB_ID").dataType("VARCHAR2").build());
        jobs.getPkColumnNames().add("JOB_ID");

        DbTable locations = dbModel.crateIfAbsent("LOCATIONS", "HR");
        DbColumn locationId = locations.addColumn(DbColumn.builder().name("LOCATION_ID").dataType("NUMBER").build());
        locations.getPkColumnNames().add("LOCATION_ID");

        DbTable employees = dbModel.crateIfAbsent("EMPLOYEES", "HR");
        DbColumn employeeId = employees.addColumn(DbColumn.builder().name("EMPLOYEE_ID").dataType("NUMBER").build());
        DbColumn empJobId = employees.addColumn(DbColumn.builder().name("JOB_ID").dataType("VARCHAR2").build());
        DbColumn empDeptId = employees.addColumn(DbColumn.builder().name("DEPARTMENT_ID").dataType("NUMBER").build());
        DbColumn managerId = employees.addColumn(DbColumn.builder().name("MANAGER_ID").dataType("NUMBER").build());
        employees.getPkColumnNames().add("EMPLOYEE_ID");

        DbTable departments = dbModel.crateIfAbsent("DEPARTMENTS", "HR");
        DbColumn departmentId = departments.addColumn(DbColumn.builder().name("DEPARTMENT_ID").dataType("NUMBER").build());
        DbColumn deptMgrId = departments.addColumn(DbColumn.builder().name("MANAGER_ID").dataType("NUMBER").build());
        DbColumn deptLocId = departments.addColumn(DbColumn.builder().name("LOCATION_ID").dataType("NUMBER").build());
        departments.getPkColumnNames().add("DEPARTMENT_ID");

        dbModel.setReferenceInfos(List.of(
            ReferenceInfo.builder().constraintName("EMP_JOB_FK").srcSchema("HR").srcTableName("EMPLOYEES").srcColumnName(empJobId.getName()).refSchema("HR").refTableName("JOBS").refColumnName(jobId.getName()).build(),
            ReferenceInfo.builder().constraintName("EMP_DEPT_FK").srcSchema("HR").srcTableName("EMPLOYEES").srcColumnName(empDeptId.getName()).refSchema("HR").refTableName("DEPARTMENTS").refColumnName(departmentId.getName()).build(),
            ReferenceInfo.builder().constraintName("EMP_MANAGER_FK").srcSchema("HR").srcTableName("EMPLOYEES").srcColumnName(managerId.getName()).refSchema("HR").refTableName("EMPLOYEES").refColumnName(employeeId.getName()).build(),
            ReferenceInfo.builder().constraintName("DEPT_MGR_FK").srcSchema("HR").srcTableName("DEPARTMENTS").srcColumnName(deptMgrId.getName()).refSchema("HR").refTableName("EMPLOYEES").refColumnName(employeeId.getName()).build(),
            ReferenceInfo.builder().constraintName("DEPT_LOC_FK").srcSchema("HR").srcTableName("DEPARTMENTS").srcColumnName(deptLocId.getName()).refSchema("HR").refTableName("LOCATIONS").refColumnName(locationId.getName()).build()
        ));
        return dbModel;
    }

    @Test
    public void indexesForTable_resolvesBareKeyWhenLookupIsSchemaQualified() {
        DbModel dbModel = new DbModel();
        IndexInfo index = new IndexInfo("employees", "department_manager", "idx_16985_dept_no", false, "btree");
        index.getColumns().add("department_id");
        dbModel.getIndexes().computeIfAbsent("department_manager", k -> new java.util.HashMap<>())
            .put(index.getIndexName(), index);

        var resolved = dbModel.indexesForTable("employees.department_manager");
        assertThat(resolved.containsKey("idx_16985_dept_no"), equalTo(true));
        assertThat(resolved.get("idx_16985_dept_no").getColumns(), contains("department_id"));
    }

    @Test
    public void indexesForTable_resolvesWhenMapKeyedBySchemaAndIndexName() {
        DbModel dbModel = new DbModel();
        IndexInfo index = new IndexInfo("public", "realm_enabled_event_types", "idx_realm_evt_types_realm", false, "btree");
        index.getColumns().add("realm_id");
        dbModel.getIndexes().computeIfAbsent("public.idx_realm_evt_types_realm", k -> new java.util.HashMap<>())
            .put(index.getIndexName(), index);

        var resolved = dbModel.indexesForTable("public.realm_enabled_event_types");
        assertThat(resolved.containsKey("idx_realm_evt_types_realm"), equalTo(true));
    }

}
