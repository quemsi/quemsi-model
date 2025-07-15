package com.quemsi.model.flow.db.sql;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.quemsi.model.flow.db.sql.DbModel.Column;
import com.quemsi.model.flow.db.sql.DbModel.DbTable;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;

public class DbModelTest {
    @Test
    public void testGivenTwoDistinctTablesWhenBuildThenShouldReflectStructure(){
        DbModel dbModel = new DbModel();
        DbTable t1 =  dbModel.crateIfAbsent("T1");
        t1.addColumn("c1", "varchar2(255)", null, null, null, null, null, null, null, null);
        t1.addColumn("c2", "bigint", null, null, null, null, null, null, null, null);

        DbTable t2 = dbModel.crateIfAbsent("T2");
        t2.addColumn("ac1", "int", null, null, null, null, null, null, null, null);
        t2.addColumn("ac2", "tinyint", null, null, null, null, null, null, null, null);
        t2.addColumn("ac3", "text", null, null, null, null, null, null, null, null);

        assertThat(t1.getColumns().size(), equalTo(2));
        assertThat(t2.getColumns().size(), equalTo(3));
        assertThat(t1.getReferencedBy().size(), equalTo(0));
        assertThat(t1.getReferences().size(), equalTo(0));
    }

    @Test
    public void testGivenTwoTablesWithSingleForeignKeyWhenBuildThenShouldReflectStructure(){
        DbModel dbModel = new DbModel();
        DbTable t1 =  dbModel.crateIfAbsent("T1");
        t1.addColumn("c1", "varchar2(255)", null, null, null, null, null, null, null, null);
        Column t1c2 = t1.addColumn("c2", "bigint", null, null, null, null, null, null, null, null);

        DbTable t2 = dbModel.crateIfAbsent("T2");
        t2.addColumn("ac1", "int", null, null, null, null, null, null, null, null);
        t2.addColumn("ac2", "tinyint", null, null, null, null, null, null, null, null);
        Column t2ac3 = t2.addColumn("ac3", "bigint", null, null, null, null, null, null, null, null);
        t2.addReference(t2ac3, t1c2, "fk_t2_t1_c2");

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
        t1.addColumn("c1", "varchar2(255)", null, null, null, null, null, null, null, null);
        Column t1c2 = t1.addColumn("c2", "bigint", null, null, null, null, null, null, null, null);

        DbTable t2 = dbModel.crateIfAbsent("T2");
        t2.addColumn("ac1", "int", null, null, null, null, null, null, null, null);
        Column t2ac2 = t2.addColumn("ac2", "tinyint", null, null, null, null, null, null, null, null);
        Column t2ac3 = t2.addColumn("ac3", "bigint", null, null, null, null, null, null, null, null);
        t2.addReference(t2ac3, t1c2, "fk_t2_t1_c2");
        
        DbTable t3 = dbModel.crateIfAbsent("T3");
        t3.addColumn("bc1", "int", null, null, null, null, null, null, null, null);
        Column t3bc2 = t3.addColumn("bc2", "tinyint", null, null, null, null, null, null, null, null);
        t3.addReference(t3bc2, t2ac2, "fk_t3_t2_ac2");
        t3.addColumn("bc3", "bigint", null, null, null, null, null, null, null, null);

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
        Column r1Id = r1.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        
        DbTable r2 =  dbModel.crateIfAbsent("R2");
        Column r2Id = r2.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        
        DbTable r3 =  dbModel.crateIfAbsent("R3");
        Column r3Id = r3.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column r3r1Id = r3.addColumn("r1_id", "bigint", null, null, null, null, null, null, null, null);

        r3.addReference(r3r1Id, r1Id, "fk_r3_r1");

        DbTable r4 =  dbModel.crateIfAbsent("R4");
        Column r4Id = r4.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        
        DbTable f1 = dbModel.crateIfAbsent("F1");
        Column f1Id = f1.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column f1r1Id = f1.addColumn("r1_id", "bigint", null, null, null, null, null, null, null, null);
        f1.addReference(f1r1Id, r1Id, "fk_f1_r1");
        Column f1r2Id = f1.addColumn("r2_id", "bigint", null, null, null, null, null, null, null, null);
        f1.addReference(f1r2Id, r2Id, "fk_f1_r2");

        DbTable f2 = dbModel.crateIfAbsent("F2");
        f2.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column f2f1Id = f2.addColumn("f1_id", "bigint", null, null, null, null, null, null, null, null);
        f2.addReference(f2f1Id, f1Id, "fk_f2_f1");
        Column f2r3Id = f2.addColumn("r3_id", "bigint", null, null, null, null, null, null, null, null);
        f2.addReference(f2r3Id, r3Id, "fk_f2_r3");

        DbTable f3 = dbModel.crateIfAbsent("F3");
        f3.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column f3r1Id = f3.addColumn("r1_id", "bigint", null, null, null, null, null, null, null, null);
        f3.addReference(f3r1Id, r1Id, "fk_f3_r1");
        Column f3r2Id = f3.addColumn("r2_id", "bigint", null, null, null, null, null, null, null, null);
        f3.addReference(f3r2Id, r2Id, "fk_f3_r2");
        Column f3r3Id = f3.addColumn("r3_id", "bigint", null, null, null, null, null, null, null, null);
        f3.addReference(f3r3Id, r3Id, "fk_f3_r3");
        Column f3r4Id = f3.addColumn("r4_id", "bingint", null, null, null, null, null, null, null, null);
        f3.addReference(f3r4Id, r4Id, "fk_f3_r4");

        DbTable f4 =  dbModel.crateIfAbsent("F4");
        f4.addColumn("id", "bigint", null, null, null, null, null, null, null, null);

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
        Column r1Id = r1.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        
        DbTable r2 =  dbModel.crateIfAbsent("R2");
        r2.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column r2r1Id = r2.addColumn("r2r1Id", "bigint", null, null, null, null, null, null, null, null);
        
        ReferenceInfo refInfo = ReferenceInfo.builder().srcTable(r2.getName()).srcColumnName(r2r1Id.getName()).refTableName(r1.getName()).refColumnName(r1Id.getName()).build();
        dbModel.getReferenceInfos().add(refInfo);

        dbModel.build();

        assertThat(r1.getReferencedBy().size(), equalTo(1));
        assertThat(r2.getReferences().size(), equalTo(1));
        assertThat(r2r1Id.getReferences().getOn(), equalTo(r1.getName()));
        assertThat(r2r1Id.getReferences().getColumn(), equalTo(r1Id.getName()));
        assertThat(dbModel.getCircularIgnore(), empty());
    }

    @Test
    public void givenTreeTablesTreeRefsWithoutCircleReferenceWhenBuildThenShouldReturnEmptyIgnoreList(){
        DbModel dbModel = new DbModel();
        DbTable r1 =  dbModel.crateIfAbsent("R1");
        Column r1Id = r1.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column r1r2Id = r1.addColumn("r2Id", "bigint", null, null, null, null, null, null, null, null);
        
        DbTable r2 =  dbModel.crateIfAbsent("R2");
        Column r2Id = r2.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        
        DbTable r3 =  dbModel.crateIfAbsent("R3");
        r3.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column r3r1Id = r3.addColumn("r1Id", "bigint", null, null, null, null, null, null, null, null);
        Column r3r2Id = r3.addColumn("r2Id", "bigint", null, null, null, null, null, null, null, null);
        

        dbModel.getReferenceInfos().addAll(List.of(
            ReferenceInfo.builder().srcTable(r1.getName()).srcColumnName(r1r2Id.getName()).refTableName(r2.getName()).refColumnName(r2Id.getName()).build(),
            ReferenceInfo.builder().srcTable(r3.getName()).srcColumnName(r3r1Id.getName()).refTableName(r1.getName()).refColumnName(r1Id.getName()).build(),
            ReferenceInfo.builder().srcTable(r3.getName()).srcColumnName(r3r2Id.getName()).refTableName(r2.getName()).refColumnName(r2Id.getName()).build()
        ));

        dbModel.build();

        assertThat(r1.getReferencedBy().size(), equalTo(1));
        assertThat(r2.getReferencedBy().size(), equalTo(2));
        assertThat(dbModel.getCircularIgnore(), empty());
    }

    @Test
    public void givenTreeTablesTreeRefsWithCircleReferenceWhenBuildThenShouldReturnEmptyIgnoreList(){
        DbModel dbModel = new DbModel();
        DbTable r1 =  dbModel.crateIfAbsent("R1");
        Column r1Id = r1.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column r1r2Id = r1.addColumn("r2Id", "bigint", null, null, null, null, null, null, null, null);
        
        DbTable r2 =  dbModel.crateIfAbsent("R2");
        Column r2Id = r2.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column r2r3Id = r2.addColumn("r3Id", "bigint", null, null, null, null, null, null, null, null);
        
        DbTable r3 =  dbModel.crateIfAbsent("R3");
        Column r3Id = r3.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column r3r1Id = r3.addColumn("r1Id", "bigint", null, null, null, null, null, null, null, null);
        

        dbModel.getReferenceInfos().addAll(List.of(
            ReferenceInfo.builder().srcTable(r1.getName()).srcColumnName(r1r2Id.getName()).refTableName(r2.getName()).refColumnName(r2Id.getName()).build(),
            ReferenceInfo.builder().srcTable(r2.getName()).srcColumnName(r2r3Id.getName()).refTableName(r3.getName()).refColumnName(r3Id.getName()).build(),
            ReferenceInfo.builder().srcTable(r3.getName()).srcColumnName(r3r1Id.getName()).refTableName(r1.getName()).refColumnName(r1Id.getName()).build()
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
        r1.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column r1r2Id = r1.addColumn("r2Id", "bigint", null, null, null, null, null, null, null, null);
        
        DbTable r2 =  dbModel.crateIfAbsent("R2");
        Column r2Id = r2.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column r2r3Id = r2.addColumn("r3Id", "bigint", null, null, null, null, null, null, null, null);
        
        DbTable r3 =  dbModel.crateIfAbsent("R3");
        Column r3Id = r3.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column r3r3Id = r3.addColumn("r3Id", "bigint", null, null, null, null, null, null, null, null);
        

        dbModel.getReferenceInfos().addAll(List.of(
            ReferenceInfo.builder().srcTable(r1.getName()).srcColumnName(r1r2Id.getName()).refTableName(r2.getName()).refColumnName(r2Id.getName()).build(),
            ReferenceInfo.builder().srcTable(r2.getName()).srcColumnName(r2r3Id.getName()).refTableName(r3.getName()).refColumnName(r3Id.getName()).build(),
            ReferenceInfo.builder().srcTable(r3.getName()).srcColumnName(r3r3Id.getName()).refTableName(r3.getName()).refColumnName(r3Id.getName()).build()
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
        Column r1Id = r1.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column r1f3Id = r1.addColumn("f3Id", "bigint", null, null, null, null, null, null, null, null);
        
        DbTable f1 =  dbModel.crateIfAbsent("F1");
        Column f1Id = f1.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column f1r1Id = f1.addColumn("r1Id", "bigint", null, null, null, null, null, null, null, null);
        Column f1r2Id = f1.addColumn("r2Id", "bigint", null, null, null, null, null, null, null, null);
        
        DbTable r2 =  dbModel.crateIfAbsent("R2");
        Column r2Id = r2.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column r2r4Id = r2.addColumn("r4Id", "bigint", null, null, null, null, null, null, null, null);

        DbTable f2 =  dbModel.crateIfAbsent("F2");
        f2.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column f2f1Id = f2.addColumn("f1Id", "bigint", null, null, null, null, null, null, null, null);
        Column f2r3Id = f2.addColumn("r3Id", "bigint", null, null, null, null, null, null, null, null);

        DbTable r3 =  dbModel.crateIfAbsent("R3");
        Column r3Id = r3.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column r3r1Id = r3.addColumn("r1Id", "bigint", null, null, null, null, null, null, null, null);

        DbTable f3 =  dbModel.crateIfAbsent("F3");
        Column f3Id = f3.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column f3r3Id = f3.addColumn("r3Id", "bigint", null, null, null, null, null, null, null, null);
        Column f3r2Id = f3.addColumn("r2Id", "bigint", null, null, null, null, null, null, null, null);

        DbTable r4 =  dbModel.crateIfAbsent("R4");
        Column r4Id = r4.addColumn("id", "bigint", null, null, null, null, null, null, null, null);
        Column r4f3Id = r4.addColumn("f3Id", "bigint", null, null, null, null, null, null, null, null);

        dbModel.crateIfAbsent("f4");
        
        

        dbModel.getReferenceInfos().addAll(List.of(
            ReferenceInfo.builder().srcTable(r1.getName()).srcColumnName(r1f3Id.getName()).refTableName(f3.getName()).refColumnName(f3Id.getName()).build(),
            ReferenceInfo.builder().srcTable(f1.getName()).srcColumnName(f1r1Id.getName()).refTableName(r1.getName()).refColumnName(r1Id.getName()).build(),
            ReferenceInfo.builder().srcTable(f1.getName()).srcColumnName(f1r2Id.getName()).refTableName(r2.getName()).refColumnName(r2Id.getName()).build(),
            ReferenceInfo.builder().srcTable(f2.getName()).srcColumnName(f2f1Id.getName()).refTableName(f1.getName()).refColumnName(f1Id.getName()).build(),
            ReferenceInfo.builder().srcTable(f2.getName()).srcColumnName(f2r3Id.getName()).refTableName(r3.getName()).refColumnName(r3Id.getName()).build(),
            ReferenceInfo.builder().srcTable(r2.getName()).srcColumnName(r2r4Id.getName()).refTableName(r4.getName()).refColumnName(r4Id.getName()).build(),
            ReferenceInfo.builder().srcTable(r3.getName()).srcColumnName(r3r1Id.getName()).refTableName(r1.getName()).refColumnName(r1Id.getName()).build(),
            ReferenceInfo.builder().srcTable(f3.getName()).srcColumnName(f3r3Id.getName()).refTableName(r3.getName()).refColumnName(r3Id.getName()).build(),
            ReferenceInfo.builder().srcTable(f3.getName()).srcColumnName(f3r2Id.getName()).refTableName(r2.getName()).refColumnName(r2Id.getName()).build(),
            ReferenceInfo.builder().srcTable(r4.getName()).srcColumnName(r4f3Id.getName()).refTableName(f3.getName()).refColumnName(f3Id.getName()).build()
        ));

        dbModel.build();

        assertThat(dbModel.getCircularIgnore().size(), equalTo(2));
    }

}
