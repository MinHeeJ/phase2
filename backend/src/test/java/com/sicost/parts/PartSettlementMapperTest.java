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
        var c = new Configuration();
        c.setMapUnderscoreToCamelCase(true); c.setArgNameBasedConstructorAutoMapping(true);
        String path = "mappers/parts/PartSettlementMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input,c,path,c.getSqlFragments()).parse();
        }
        return c;
    }
    @Test void everyProjectionMatchesExactConstructorLabelsTypesAndOrderWithoutDatabase() throws Exception {
        var c = configuration(); String ns = PartSettlementMapper.class.getName()+".";
        for (var method : PartSettlementMapper.class.getDeclaredMethods()) assertThat(c.hasStatement(ns+method.getName())).isTrue();
        var projections = Map.of("part",Part.class,"standard",Standard.class,"person",Person.class,
            "settlement",Settlement.class,"item",Item.class,"history",History.class);
        for (var entry : projections.entrySet()) {
            var components = entry.getValue().getRecordComponents();
            var mappings = c.getResultMap(ns+entry.getKey()).getConstructorResultMappings();
            assertThat(mappings).hasSize(components.length);
            for (int i=0;i<components.length;i++) {
                assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
                assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
            }
        }
    }
    @Test void stableParentThenRowLocksAndConditionalDraftTransitionExist() throws Exception {
        var c = configuration(); String ns = PartSettlementMapper.class.getName()+".";
        for (String method : List.of("lockPart","lockSettlement")) {
            var statement = c.getMappedStatement(ns+method);
            assertThat(statement.getBoundSql(Map.of()).getSql()).contains("FOR UPDATE");
            assertThat(statement.isUseCache()).isFalse();
        }
        assertThat(c.getMappedStatement(ns+"saveCard").getBoundSql(Map.of()).getSql())
            .contains("RETURNING stlm_id","ON CONFLICT(bsn_part_id,stlm_ym)","stlm_stts_cd='DRAFT'");
        assertThat(c.getMappedStatement(ns+"transition").getBoundSql(Map.of()).getSql())
            .contains("stlm_stts_cd=?").doesNotContain("tot_sls_amt=","slry_amt=");
    }
    @Test void monthlyOwnershipComesFromPayrollAndEarliestNoncancelledStart() throws Exception {
        var c = configuration();
        String sql = c.getMappedStatement(PartSettlementMapper.class.getName()+".people").getBoundSql(Map.of()).getSql();
        assertThat(sql).contains("c.bsn_part_id=?","c.cst_ym=?","min(a.bgn_dt)","a.asgn_stts_cd!='CANCELLED'","c.real_pay_amt");
        assertThat(sql).doesNotContain("d.bsn_part_id","p.bsn_part_id","indv_lbr_cst_amt");
    }
    @Test void cardRemarkReadbackUsesLatestCardAuditAndCancelKeepsSnapshot() throws Exception {
        var c = configuration(); String ns = PartSettlementMapper.class.getName()+".";
        assertThat(c.getMappedStatement(ns+"settlement").getBoundSql(Map.of()).getSql())
            .contains("a.clmn_nm='corp_card_amt'","a.chg_at DESC,a.data_chg_hist_id DESC","aft_val::jsonb");
        assertThat(c.getMappedStatement(ns+"historyWrite").getBoundSql(Map.of()).getSql()).contains("'CANCEL'");
    }
}
