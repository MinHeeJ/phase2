package com.sicost.billingsops;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class BillingReviewMapperTest {
    Configuration configuration() throws Exception {
        var c=new Configuration();String path="mappers/billingsops/BillingReviewMapper.xml";
        try(var in=new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();
        }
        return c;
    }
    @Test void everyConstructorHasExactLabelsAndTypesWithoutDatabase() throws Exception {
        var c=configuration();String namespace=BillingReviewMapper.class.getName();
        for(var m:BillingReviewMapper.class.getDeclaredMethods()) assertThat(c.hasStatement(namespace+"."+m.getName())).as(m.getName()).isTrue();
        for(var name:List.of("bill","header","line","receipt","history","project","stage","source","part")) {
            var map=c.getResultMap(namespace+"."+name);var components=map.getType().getRecordComponents();
            var args=map.getConstructorResultMappings();assertThat(args).hasSize(components.length);
            for(int i=0;i<components.length;i++) {
                assertThat(args.get(i).getColumn()).isEqualTo(components[i].getName());
                assertThat(args.get(i).getJavaType()).isEqualTo(components[i].getType());
            }
        }
    }
    @Test void writesUseGeneratedHeaderKeyAndConditionalStatusNoSourceMutation() throws Exception {
        var c=configuration();String ns=BillingReviewMapper.class.getName()+".";
        String insert=c.getMappedStatement(ns+"insertAmendment").getBoundSql(null).getSql();
        assertThat(insert).contains("RETURNING bill_id").doesNotContain("tot_amt", "bill_stg_id");
        String transition=c.getMappedStatement(ns+"transition").getBoundSql(null).getSql();
        assertThat(transition).contains("AND bill_stts_cd=?").doesNotContain("sply_amt=","bill.bill_dtl","asgn.asgn");
        assertThat(c.getMappedStatement(ns+"receipts").getBoundSql(1).getSql()).contains("r.rcpt_id AS receipt_id");
    }
    @Test void sourcePriceUsesEffectiveAssignmentDateAndNeverNullPredicates() throws Exception {
        var sql=configuration().getMappedStatement(BillingReviewMapper.class.getName()+".sources")
            .getBoundSql(new BillingReviewModels.PreviewKey(1,"2026-10",null)).getSql();
        assertThat(sql).contains("greatest(","a.bgn_dt)","h.aply_bgn_dt DESC").doesNotContain("? IS NULL OR");
    }
}
