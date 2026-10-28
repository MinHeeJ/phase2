package com.sicost.billings;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static com.sicost.billings.BillingModels.*;

class BillingMapperTest {
    @Test void xmlRegistersExactMapperAndConstructorMapsAndUsesSameDynamicFilters() throws Exception {
        Configuration configuration = new Configuration();
        var resource = new ClassPathResource("mappers/billings/BillingMapper.xml");
        try (var in = resource.getInputStream()) {
            new XMLMapperBuilder(in, configuration, resource.toString(), configuration.getSqlFragments()).parse();
        }
        assertThat(configuration.hasMapper(BillingMapper.class)).isTrue();
        var filter = new BillingFilter(2026,"DRAFT","MO",9L,"검색",0,20,"bill_no","asc");
        var list = configuration.getMappedStatement("com.sicost.billings.BillingMapper.list").getBoundSql(filter);
        var count = configuration.getMappedStatement("com.sicost.billings.BillingMapper.count").getBoundSql(filter);
        var export = configuration.getMappedStatement("com.sicost.billings.BillingMapper.export").getBoundSql(filter);
        assertThat(list.getParameterMappings().stream().map(p -> p.getProperty()).toList())
            .startsWith(count.getParameterMappings().stream().map(p -> p.getProperty()).toArray(String[]::new));
        assertThat(export.getParameterMappings().stream().map(p -> p.getProperty()).toList())
            .containsExactlyElementsOf(count.getParameterMappings().stream().map(p -> p.getProperty()).toList());
        assertThat(export.getSql()).doesNotContain("LIMIT", "OFFSET")
            .contains("ORDER BY b.bill_no asc NULLS LAST,b.bill_id DESC NULLS LAST,b.bill_stg_id DESC");
        assertThat(list.getSql().substring(0, list.getSql().indexOf("LIMIT")).replaceAll("\\s+", " ").trim())
            .isEqualTo(export.getSql().replaceAll("\\s+", " ").trim());
        assertThat(configuration.getResultMap("com.sicost.billings.BillingMapper.row").getConstructorResultMappings())
            .hasSize(BillingRow.class.getRecordComponents().length);
        assertThat(configuration.getResultMap("com.sicost.billings.BillingMapper.detail").getConstructorResultMappings())
            .hasSize(DetailLine.class.getRecordComponents().length);
    }
}
