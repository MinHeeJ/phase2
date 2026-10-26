package com.si.cost.shared;

import com.si.cost.common.user.DataScope;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

class SharedReadMapperTest {
    private Configuration configuration() throws Exception {
        var config = new Configuration();
        config.setArgNameBasedConstructorAutoMapping(true);
        String resource = "mappers/shared/SharedReadMapper.xml";
        try (var stream = new ClassPathResource(resource).getInputStream()) {
            new XMLMapperBuilder(stream, config, resource, config.getSqlFragments()).parse();
        }
        return config;
    }
    private String sql(Configuration config, String method, Map<String, Object> parameters) {
        return config.getMappedStatement("com.si.cost.shared.SharedReadMapper." + method)
                .getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
    }
    @Test void mapperResourceRegistersEverySharedReadAndUsesBoundPhysicalKeys() throws Exception {
        var config = configuration();
        assertThat(config.hasMapper(SharedReadMapper.class)).isTrue();
        assertThat(sql(config, "menus", Map.of("scope", DataScope.ALL)))
                .contains("FROM sys.menu WHERE use_yn = 'Y' ORDER BY sort_seq, menu_id", "upr_menu_id AS \"parentId\"");
        assertThat(sql(config, "codeOptions", Map.of("group", "JOB", "scope", DataScope.ALL)))
                .contains("cd_grp_id = ? AND use_yn = 'Y'", "ORDER BY sort_seq, dtl_cd").doesNotContain("JOB");
        assertThat(sql(config, "monthClose", Map.of("from", "2026-09", "to", "2026-10", "scope", DataScope.ALL)))
                .contains("cls_ym >= ? AND cls_ym <= ? ORDER BY cls_ym");
        assertThat(sql(config, "effectiveParam", Map.of("key", "VAT_RATE", "month", "2026-10", "scope", DataScope.ALL)))
                .contains("FROM cmn.oprt_std WHERE oprt_std_id = ? AND aply_bgn_ym <= ?", "ORDER BY aply_bgn_ym DESC LIMIT 1")
                .doesNotContain("VAT_RATE");
        assertThat(sql(config, "groupExists", Map.of("group", "JOB"))).contains("FROM cmn.cd_grp WHERE cd_grp_id = ?");
    }
}
