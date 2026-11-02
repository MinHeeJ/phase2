package com.sicost.closingops;

import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.closingops.ClosingModels.*;

class ClosingMapperTest {
    Configuration configuration() throws Exception {
        var config=new Configuration();config.setMapUnderscoreToCamelCase(true);config.setArgNameBasedConstructorAutoMapping(true);
        for(String path:List.of("mappers/profit/MonthlyProfitMapper.xml","mappers/closingops/ClosingMapper.xml"))
            try(var stream=new ClassPathResource(path).getInputStream()){new XMLMapperBuilder(stream,config,path,config.getSqlFragments()).parse();}
        return config;
    }
    @Test void everyMethodHasMappedStatementAndConstructorsMatchRecords() throws Exception {
        var config=configuration();String ns=ClosingMapper.class.getName()+".";
        for(var method:ClosingMapper.class.getDeclaredMethods())assertThat(config.hasStatement(ns+method.getName())).isTrue();
        for(var entry:Map.of("check",Check.class,"history",HistoryRow.class,"preview",StoredPreview.class).entrySet()) {
            var mappings=config.getResultMap(ns+entry.getKey()).getConstructorResultMappings();var fields=entry.getValue().getRecordComponents();
            assertThat(mappings).hasSize(fields.length);
            for(int i=0;i<fields.length;i++){assertThat(mappings.get(i).getColumn()).isEqualTo(fields[i].getName());assertThat(mappings.get(i).getJavaType()).isEqualTo(fields[i].getType());}
        }
    }
    @Test void historyCountAndPageUseIdenticalYearFilterWithStableDescendingIdentity() throws Exception {
        var config=configuration();String ns=ClosingMapper.class.getName()+".";var q=new HistoryQuery(2026,1,3,3);
        String list=config.getMappedStatement(ns+"history").getBoundSql(q).getSql();String count=config.getMappedStatement(ns+"historyCount").getBoundSql(q).getSql();
        assertThat(list).contains("left(h.cls_ym,4)=lpad(","h.prcs_at DESC,h.cls_hist_id DESC","LIMIT ? OFFSET ?");
        assertThat(count).contains("left(h.cls_ym,4)=lpad(");
    }
    @Test void reopenDeletesOnlyCloseProfitAndLeavesImportAndSourceTablesAlone() throws Exception {
        var config=configuration();String ns=ClosingMapper.class.getName()+".";
        assertThat(config.getMappedStatement(ns+"deleteProfit").getBoundSql(Map.of("month","2026-10")).getSql()).contains("pl_src_cd='CLOSE'");
        for(String statement:List.of("deleteProfit","deleteAllocations","deleteTax","clearCash"))assertThat(config.getMappedStatement(ns+statement).getBoundSql(Map.of("month","2026-10")).getSql()).doesNotContain("cst.mth_cst","mth_cls_hist","prvmn_adj","bill.bill");
    }
}
