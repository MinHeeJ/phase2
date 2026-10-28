package com.sicost.billings;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.billings.BillingModels.*;
import java.time.LocalDate;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class BillingMapperTest {
    Configuration configuration() throws Exception {
        var c = new Configuration();
        String path = "mappers/billings/BillingMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input,c,path,c.getSqlFragments()).parse();
        }
        return c;
    }
    String sql(Configuration c, String method, Object parameter) {
        return c.getMappedStatement(BillingMapper.class.getName()+"."+method).getBoundSql(parameter).getSql();
    }
    @Test void registersEveryStatementAndEveryRecordConstructor() throws Exception {
        var c=configuration();
        for(var method:BillingMapper.class.getDeclaredMethods()) {
            assertThat(c.hasStatement(BillingMapper.class.getName()+"."+method.getName())).isTrue();
        }
        for(var entry:Map.of("project",Project.class,"stage",Stage.class,"state",State.class,"row",Row.class,
            "line",Line.class,"source",Source.class,"receipt",Receipt.class,"history",History.class).entrySet()) {
            var map=c.getResultMap(BillingMapper.class.getName()+"."+entry.getKey());
            assertThat(map.getType()).isEqualTo(entry.getValue());
            assertThat(map.getConstructorResultMappings()).hasSize(entry.getValue().getRecordComponents().length);
        }
    }
    @Test void filtersBindOnlyPresentValuesAndCountSharesThem() throws Exception {
        var c=configuration();
        var all=new Query(2026,null,null,null,null,0,50,0,"bill_id desc",LocalDate.of(2026,10,22));
        var filtered=new Query(2026,"DRAFT","MO",2L,"검색",0,20,0,"bill_id desc",all.today());
        String bare=sql(c,"list",all), list=sql(c,"list",filtered), count=sql(c,"count",filtered);
        assertThat(bare).doesNotContain("x.customer_id=?","x.bill_stts_cd=?","IS NULL OR");
        assertThat(list).contains("x.customer_id=?","x.bill_stts_cd=?","x.contract_type=?");
        assertThat(count.substring(count.indexOf("WHERE extract(year")).trim())
            .isEqualTo(list.substring(list.indexOf("WHERE extract(year"),list.lastIndexOf("ORDER BY" )).trim());
    }
}
