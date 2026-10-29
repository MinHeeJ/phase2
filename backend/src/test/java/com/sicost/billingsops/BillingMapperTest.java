package com.sicost.billingsops;

import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.billingsops.BillingModels.*;

class BillingMapperTest {
    Configuration configuration()throws Exception {
        var c=new Configuration();c.setMapUnderscoreToCamelCase(true);c.setArgNameBasedConstructorAutoMapping(true);
        String path="mappers/billingsops/BillingMapper.xml";
        try(var input=new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input,c,path,c.getSqlFragments()).parse();
        }
        return c;
    }
    @Test void everyProjectionMatchesExactConstructorLabelsTypesAndOrder()throws Exception {
        var c=configuration();String ns=BillingMapper.class.getName()+".";
        for(var method:BillingMapper.class.getDeclaredMethods())assertThat(c.hasStatement(ns+method.getName())).isTrue();
        var projections=Map.ofEntries(Map.entry("project",Project.class),Map.entry("stage",Stage.class),
            Map.entry("source",Source.class),Map.entry("state",State.class),Map.entry("line",Line.class),
            Map.entry("balance",Balance.class),Map.entry("confirmed",Confirmed.class),Map.entry("receipt",Receipt.class),
            Map.entry("history",History.class),Map.entry("sent",Sent.class),Map.entry("revenue",Revenue.class),
            Map.entry("projectRevenue",ProjectRevenue.class),Map.entry("cash",Cash.class));
        for(var entry:projections.entrySet()) {
            var components=entry.getValue().getRecordComponents();
            var mappings=c.getResultMap(ns+entry.getKey()).getConstructorResultMappings();
            assertThat(mappings).hasSize(components.length);
            for(int i=0;i<components.length;i++) {
                assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
                assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
            }
        }
    }
    @Test void rootBalancesExcludeDraftAndCancelledAdjustmentsAndNeverUseLegacyView()throws Exception {
        var c=configuration();
        for(String method:List.of("state","states","balance")) {
            String sql=c.getMappedStatement(BillingMapper.class.getName()+"."+method).getBoundSql(7).getSql();
            assertThat(sql).contains("x.bill_stts_cd='CONFIRM'","x.orgnl_bill_id=b.bill_id").doesNotContain("vw_bill_unrcv","CURRENT_DATE");
        }
    }
    @Test void commandsNeverWriteSourceOrGeneratedTotalOrDetailSnapshots()throws Exception {
        var c=configuration();String ns=BillingMapper.class.getName()+".";
        String insert=c.getMappedStatement(ns+"insertAmendment").getBoundSql(Map.of()).getSql();
        assertThat(insert).contains("RETURNING bill_id","'DRAFT'").doesNotContain("tot_amt","bill_dtl","bill_stg_id");
        String update=c.getMappedStatement(ns+"transition").getBoundSql(Map.of()).getSql();
        assertThat(update).contains("bill_stts_cd=?").doesNotContain("sply_amt=","atrb_ym=","bill_dtl");
        for(String method:List.of("lockBill","lockProject"))
            assertThat(c.getMappedStatement(ns+method).getBoundSql(1).getSql()).contains("FOR UPDATE");
    }
    @Test void bothNumberAllocatorsUseTheSameUncachedMonthlyLock() throws Exception {
        var c = configuration();
        String path = "mappers/billings/BillingMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input, c, path, c.getSqlFragments()).parse();
        }
        var normal = c.getMappedStatement("com.sicost.billings.BillingMapper.numberLock");
        var amendment = c.getMappedStatement(BillingMapper.class.getName() + ".lockNumber");
        assertThat(amendment.getBoundSql("B-2610-").getSql().replaceAll("\\s+", " ").trim())
            .isEqualTo(normal.getBoundSql("B-2610-").getSql().replaceAll("\\s+", " ").trim());
        assertThat(amendment.isUseCache()).isFalse();
        assertThat(amendment.isFlushCacheRequired()).isTrue();
    }
}
