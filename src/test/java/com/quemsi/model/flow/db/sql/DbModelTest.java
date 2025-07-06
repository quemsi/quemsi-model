package com.quemsi.model.flow.db.sql;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.quemsi.model.flow.db.sql.DbModel.DbTable;

public class DbModelTest {
    @Test
    public void testGivenTwoDistinctTablesWhenBuildThenShouldReflectStructure(){
        DbModel dbModel = new DbModel();
        DbTable t1 =  dbModel.crateIfAbsent("T1");
        t1.addColumn("c1", "varchar2(255)", null, null, null, null, null, null, null, null, null, null);
        t1.addColumn("c2", "bigint", null, null, null, null, null, null, null, null, null, null);

        DbTable t2 = dbModel.crateIfAbsent("T2");
        t2.addColumn("ac1", "int", null, null, null, null, null, null, null, null, null, null);
        t2.addColumn("ac2", "tinyint", null, null, null, null, null, null, null, null, null, null);
        t2.addColumn("ac3", "text", null, null, null, null, null, null, null, null, null, null);

        assertThat(t1.getColumns().size(), equalTo(2));
        assertThat(t2.getColumns().size(), equalTo(3));
        assertThat(t1.getReferencedBy().size(), equalTo(0));
        assertThat(t1.getReferences().size(), equalTo(0));
    }

    @Test
    public void testGivenTwoTablesWithSingleForeignKeyWhenBuildThenShouldReflectStructure(){
        DbModel dbModel = new DbModel();
        DbTable t1 =  dbModel.crateIfAbsent("T1");
        t1.addColumn("c1", "varchar2(255)", null, null, null, null, null, null, null, null, null, null);
        t1.addColumn("c2", "bigint", null, null, null, null, null, null, null, null, null, null);

        DbTable t2 = dbModel.crateIfAbsent("T2");
        t2.addColumn("ac1", "int", null, null, null, null, null, null, null, null, null, null);
        t2.addColumn("ac2", "tinyint", null, null, null, null, null, null, null, null, null, null);
        t2.addColumn("ac3", "bigint", t1.findColumn("c2").get(), "fk_t2_t1_c2", null, null, null, null, null, null, null, null);

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
        t1.addColumn("c1", "varchar2(255)", null, null, null, null, null, null, null, null, null, null);
        t1.addColumn("c2", "bigint", null, null, null, null, null, null, null, null, null, null);

        DbTable t2 = dbModel.crateIfAbsent("T2");
        t2.addColumn("ac1", "int", null, null, null, null, null, null, null, null, null, null);
        t2.addColumn("ac2", "tinyint", null, null, null, null, null, null, null, null, null, null);
        t2.addColumn("ac3", "bigint", t1.findColumn("c2").get(), "fk_t2_t1_c2", null, null, null, null, null, null, null, null);

        DbTable t3 = dbModel.crateIfAbsent("T3");
        t3.addColumn("bc1", "int", null, null, null, null, null, null, null, null, null, null);
        t3.addColumn("bc2", "tinyint", t2.findColumn("ac2").get(), "fk_t3_t2_ac2", null, null, null, null, null, null, null, null);
        t3.addColumn("bc3", "bigint", null, null, null, null, null, null, null, null, null, null);

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
        r1.addColumn("id", "bigint", null, null, null, null, null, null, null, null, null, null);
        
        DbTable r2 =  dbModel.crateIfAbsent("R2");
        r2.addColumn("id", "bigint", null, null, null, null, null, null, null, null, null, null);
        
        DbTable r3 =  dbModel.crateIfAbsent("R3");
        r3.addColumn("id", "bigint", null, null, null, null, null, null, null, null, null, null);
        r3.addColumn("r1_id", "bigint", r1.findColumn("id").get(), "fk_r3_r1", null, null, null, null, null, null, null, null);

        DbTable r4 =  dbModel.crateIfAbsent("R4");
        r4.addColumn("id", "bigint", null, null, null, null, null, null, null, null, null, null);
        
        DbTable f1 = dbModel.crateIfAbsent("F1");
        f1.addColumn("id", "bigint", null, null, null, null, null, null, null, null, null, null);
        f1.addColumn("r1_id", "bigint", r1.findColumn("id").get(), "fk_f1_r1", null, null, null, null, null, null, null, null);
        f1.addColumn("r2_id", "bigint", r2.findColumn("id").get(), "fk_f1_r2", null, null, null, null, null, null, null, null);

        DbTable f2 = dbModel.crateIfAbsent("F2");
        f2.addColumn("id", "bigint", null, null, null, null, null, null, null, null, null, null);
        f2.addColumn("f1_id", "bigint", f1.findColumn("id").get(), "fk_f2_f1", null, null, null, null, null, null, null, null);
        f2.addColumn("r3_id", "bigint", r3.findColumn("id").get(), "fk_f2_r3", null, null, null, null, null, null, null, null);

        DbTable f3 = dbModel.crateIfAbsent("F3");
        f3.addColumn("id", "bigint", null, null, null, null, null, null, null, null, null, null);
        f3.addColumn("r1_id", "bigint", r1.findColumn("id").get(), "fk_f3_r1", null, null, null, null, null, null, null, null);
        f3.addColumn("r2_id", "bigint", r2.findColumn("id").get(), "fk_f3_r2", null, null, null, null, null, null, null, null);
        f3.addColumn("r3_id", "bigint", r3.findColumn("id").get(), "fk_f3_r3", null, null, null, null, null, null, null, null);
        f3.addColumn("r4_id", "bigint", r4.findColumn("id").get(), "fk_f3_r4", null, null, null, null, null, null, null, null);

        DbTable f4 =  dbModel.crateIfAbsent("F4");
        f4.addColumn("id", "bigint", null, null, null, null, null, null, null, null, null, null);
        


        List<DbTable> sorted = dbModel.sortedTableList();
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
}
