package com.si.cost.admin;

import com.si.cost.common.api.PageRequest;
import com.si.cost.common.user.DataScope;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

class AdminMapperTest {
    private Configuration configuration() throws Exception {
        var config=new Configuration(); config.setArgNameBasedConstructorAutoMapping(true);
        String resource="mappers/admin/AdminMapper.xml";
        try(var stream=new ClassPathResource(resource).getInputStream()) { new XMLMapperBuilder(stream,config,resource,config.getSqlFragments()).parse(); }
        return config;
    }
    private String sql(Configuration config,String method,Map<String,Object> params) {
        return config.getMappedStatement("com.si.cost.admin.AdminMapper."+method).getBoundSql(params).getSql().replaceAll("\\s+"," ").trim();
    }
    @Test void everyMapperStatementParsesAndListsHaveBoundPagination() throws Exception {
        var config=configuration(); assertThat(config.hasMapper(AdminMapper.class)).isTrue();
        for(String method:List.of("groups","codes","maps","history","currentParams","versions")) {
            var params=new HashMap<String,Object>(); params.put("page",new PageRequest(1,50,"sort_seq",false)); params.put("scope",DataScope.ALL); params.put("group","JOB"); params.put("key","VAT_RATE"); params.put("month","2026-10");
            String query=sql(config,method,params); assertThat(query).contains("LIMIT ? OFFSET ?").doesNotContain("${","IS NULL OR");
            var bound=config.getMappedStatement("com.si.cost.admin.AdminMapper."+method).getBoundSql(params);
            assertThat(bound.getAdditionalParameter("offset")).isEqualTo(50);
        }
    }
    @Test void historyOptionalFilterIsAddedToListAndCountOnlyWhenPresent() throws Exception {
        var config=configuration(); var params=new HashMap<String,Object>(); params.put("group",null); params.put("page",new PageRequest(0,50,"chg_at",true));
        assertThat(sql(config,"history",params)).doesNotContain("WHERE h.cd_grp_id"); assertThat(sql(config,"historyCount",params)).doesNotContain("WHERE");
        params.put("group","PARAM"); assertThat(sql(config,"history",params)).contains("WHERE h.cd_grp_id=?"); assertThat(sql(config,"historyCount",params)).contains("WHERE h.cd_grp_id=?");
    }
    @Test void mappingNamesDoNotFilterInactiveReferencesAndInsertReturnsGeneratedId() throws Exception {
        var config=configuration(); var page=Map.<String,Object>of("page",new PageRequest(0,50,"excel_lbl_nm",false));
        assertThat(sql(config,"maps",page)).contains("JOIN cmn.cd_dtl","m.map_id::text AS id").doesNotContain("use_yn='Y'");
        assertThat(sql(config,"mapCount",Map.of())).contains("JOIN cmn.cd_dtl");
        assertThat(sql(config,"insertMap",Map.of())).contains("RETURNING map_id").doesNotContain("updated_at");
    }
    @Test void dynamicUpdatesOnlyTouchRequestedColumnsAndNeverBusinessTables() throws Exception {
        var config=configuration(); String query=sql(config,"updateCode",Map.of("changes",Map.of("cd_nm","이름"),"group","JOB","code","BE"));
        assertThat(query).contains("cd_nm=?","updated_at=CURRENT_TIMESTAMP","updated_by=NULL").doesNotContain("attr1_val=","use_yn=");
        query=sql(config,"updateMap",Map.of("changes",Map.of("rmrk_desc","메모"),"id",1));
        assertThat(query).contains("UPDATE cmn.excel_map SET rmrk_desc=?").doesNotContain("excel_lbl_nm=","updated_at","trns_cd=");
    }
    @Test void parameterMetadataAndCurrentBoundaryArePreservedBySql() throws Exception {
        var config=configuration(); var params=Map.<String,Object>of("month","2026-10","page",new PageRequest(0,50,"oprt_std_id",false));
        assertThat(sql(config,"currentParams",params)).contains("aply_bgn_ym <= ? AND NOT EXISTS","newer.aply_bgn_ym > p.aply_bgn_ym");
        assertThat(sql(config,"currentParamCount",params)).contains("aply_bgn_ym <= ? AND NOT EXISTS","newer.aply_bgn_ym > p.aply_bgn_ym");
        assertThat(sql(config,"saveParam",Map.of())).contains("oprt_std_desc","ON CONFLICT(oprt_std_id,aply_bgn_ym) DO UPDATE SET num_val=EXCLUDED.num_val,txt_val=EXCLUDED.txt_val").doesNotContain("updated_at");
        assertThat(sql(config,"lastClosed",Map.of())).contains("MAX(cls_ym)","cls_stts_cd='CLOSED'");
    }
}
