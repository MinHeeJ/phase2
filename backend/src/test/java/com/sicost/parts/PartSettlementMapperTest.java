package com.sicost.parts;

import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.parts.PartSettlementModels.*;

class PartSettlementMapperTest {
    Configuration configuration() throws Exception {
        var c=new Configuration();
        c.setMapUnderscoreToCamelCase(true); c.setArgNameBasedConstructorAutoMapping(true);
        String path="mappers/parts/PartSettlementMapper.xml";
        try(var input=new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input,c,path,c.getSqlFragments()).parse();
        }
        return c;
    }
    @Test void everyProjectionMatchesRecordConstructorLabelsTypesAndOrder() throws Exception {
        var c=configuration(); String ns=PartSettlementMapper.class.getName()+".";
        for(var method:PartSettlementMapper.class.getDeclaredMethods()) assertThat(c.hasStatement(ns+method.getName())).isTrue();
        var projections=Map.of("part",Part.class,"standard",Standard.class,"header",Header.class,
            "salary",Salary.class,"person",Person.class,"history",History.class);
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
    @Test void lockCreationAndConditionalWritesUseNaturalKeyAndExistingSchema() throws Exception {
        var c=configuration(); String ns=PartSettlementMapper.class.getName()+".";
        assertThat(c.getMappedStatement(ns+"lockPart").getBoundSql(7).getSql()).contains("FOR UPDATE","mst.bsn_part");
        assertThat(c.getMappedStatement(ns+"lockHeader").getBoundSql(new Key(7,"2026-10")).getSql())
            .contains("FOR UPDATE","s.bsn_part_id=?","s.stlm_ym=?");
        assertThat(c.getMappedStatement(ns+"ensureDraft").getBoundSql(new Key(7,"2026-10")).getSql())
            .contains("ON CONFLICT (bsn_part_id,stlm_ym) DO NOTHING");
        assertThat(c.getMappedStatement(ns+"confirm").getBoundSql(Map.of()).getSql()).contains("stlm_stts_cd='DRAFT'");
        assertThat(c.getMappedStatement(ns+"cancel").getBoundSql(Map.of()).getSql()).contains("stlm_stts_cd='CONFIRM'");
        assertThat(c.getMappedStatement(ns+"note").getBoundSql(1).getSql())
            .contains("h.rsn_desc","h.clmn_nm='corp_card_amt'","h.chg_at DESC,h.data_chg_hist_id DESC");
    }
    @Test void salaryAttributionAndProrationSourceNeverUseCurrentPartOrGeneratedLabor() throws Exception {
        var c=configuration(); String ns=PartSettlementMapper.class.getName()+".";
        String sql=c.getMappedStatement(ns+"salaries").getBoundSql(new Key(7,"2026-10")).getSql();
        assertThat(sql).contains("s.bsn_part_id=?","s.slry_amt","s.rtrpay_acml_amt","min(a.bgn_dt)")
            .doesNotContain("d.bsn_part_id","real_pay_amt","indv_lbr_cst_amt");
    }
}
