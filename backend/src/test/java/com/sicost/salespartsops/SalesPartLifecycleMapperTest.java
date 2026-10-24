package com.sicost.salespartsops;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.salespartsops.SalesPartLifecycleModels.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SalesPartLifecycleMapperTest {
    Configuration configuration()throws Exception {
        var configuration=new Configuration();
        for(String path:List.of("mappers/salesparts/SalesPartMapper.xml","mappers/salespartsops/SalesPartLifecycleMapper.xml"))
            try(var in=new ClassPathResource(path).getInputStream()){new XMLMapperBuilder(in,configuration,path,configuration.getSqlFragments()).parse();}
        return configuration;
    }
    @Test void everyStatementRegisteredAndExistingRecordMappingReused()throws Exception {
        var config=configuration();
        for(var method:SalesPartLifecycleMapper.class.getDeclaredMethods()) assertThat(config.hasStatement(SalesPartLifecycleMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();
        assertThat(config.getMappedStatement(SalesPartLifecycleMapper.class.getName()+".lock").getResultMaps().get(0).getType()).isEqualTo(com.sicost.salesparts.SalesPartModels.PartRow.class);
    }
    @Test void exportAllHasNoPaginationOrInactiveFilterAndOptionalIdsAreDynamic()throws Exception {
        var statement=configuration().getMappedStatement(SalesPartLifecycleMapper.class.getName()+".exportRows");
        var all=statement.getBoundSql(new SalesPartExportQuery(null,"2026-10",LocalDate.of(2026,10,22)));
        assertThat(all.getSql()).doesNotContain("OFFSET", "p.use_yn=", "p.bsn_part_id IN", "? IS NULL", "COALESCE(?");
        assertThat(all.getParameterMappings()).allMatch(p->Set.of("month","today").contains(p.getProperty()));
        var selected=statement.getBoundSql(new SalesPartExportQuery(List.of(2L,101L),"2026-10",LocalDate.of(2026,10,22)));
        assertThat(selected.getSql()).contains("p.bsn_part_id IN", "ORDER BY p.sort_seq,p.bsn_part_id", "AS \"currentStandard.bsn_fee_amt\"");
        assertThat(selected.getParameterMappings().size()).isEqualTo(all.getParameterMappings().size()+2);
    }
    @Test void standardsWriteBindsRecordComponentsAndKeepsLeaderAccountNull()throws Exception {
        var config=configuration();var input=new StandardReplacement(2,new StandardSave("2026-11","파트장",new BigDecimal("45"),1500000),"2026-10",null);
        var insert=config.getMappedStatement(SalesPartLifecycleMapper.class.getName()+".insertStandard").getBoundSql(input);
        assertThat(insert.getSql()).contains("NULL,NULL", "RETURNING part_std_hist_id");
        var close=config.getMappedStatement(SalesPartLifecycleMapper.class.getName()+".closePrevious").getBoundSql(input);
        assertThat(close.getSql()).contains("aply_bgn_ym<", "aply_end_ym>=");
        assertThat(config.getMappedStatement(SalesPartLifecycleMapper.class.getName()+".options").getBoundSql(null).getSql()).contains("use_yn='Y'", "ORDER BY sort_seq,bsn_part_id");
    }
}
