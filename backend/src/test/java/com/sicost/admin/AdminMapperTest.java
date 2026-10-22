package com.sicost.admin;

import com.sicost.admin.AdminModels.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class AdminMapperTest {
    private Configuration configuration;
    @BeforeEach void parseRealMapper() throws Exception {
        configuration=new Configuration();
        try(var stream=new ClassPathResource("mappers/admin/AdminMapper.xml").getInputStream()) {
            new XMLMapperBuilder(stream,configuration,"mappers/admin/AdminMapper.xml",configuration.getSqlFragments()).parse();
        }
    }
    private String sql(String method,Object parameter) { return configuration.getMappedStatement("com.sicost.admin.AdminMapper."+method).getBoundSql(parameter).getSql().replaceAll("\\s+"," ").trim(); }
    @Test void everyTypedMapperMethodHasAStatementAndRecordConstructorMatches() {
        for(var method:AdminMapper.class.getDeclaredMethods()) assertThat(configuration.hasStatement("com.sicost.admin.AdminMapper."+method.getName())).as(method.getName()).isTrue();
        for(String name:List.of("groupResult","codeResult","excelResult","paramResult","historyResult")) {
            var result=configuration.getResultMap("com.sicost.admin.AdminMapper."+name);
            assertThat(result.getType().isRecord()).isTrue(); assertThat(result.getConstructorResultMappings()).hasSize(result.getType().getRecordComponents().length);
        }
    }
    @Test void optionalHistoryPredicateAndCountShareSameFilter() {
        var filtered=new AdminListQuery("JOB",null,null,"h.chg_at DESC, h.cd_hist_id DESC",0,50);
        var unfiltered=new AdminListQuery(null,null,null,"h.chg_at DESC, h.cd_hist_id DESC",0,50);
        assertThat(sql("history",filtered)).contains("WHERE h.cd_grp_id=?"); assertThat(sql("historyCount",filtered)).contains("WHERE h.cd_grp_id=?");
        assertThat(sql("history",unfiltered)).doesNotContain("WHERE"); assertThat(sql("historyCount",unfiltered)).doesNotContain("WHERE");
    }
    @Test void inactiveNamesPreservedAndNoBusinessTableIsUpdated() {
        assertThat(sql("excelMaps",new AdminListQuery(null,null,null,"e.excel_lbl_nm ASC, e.map_id ASC",0,50))).contains("JOIN cmn.cd_dtl").doesNotContain("use_yn='Y'");
        assertThat(sql("patchExcel",new ExcelPatch(1L,Map.of("use_yn","N")))).isEqualTo("UPDATE cmn.excel_map SET use_yn=? WHERE map_id=?");
    }
    @Test void codeUpdateOnlyTouchesChangedColumnAndAudit() {
        var row=new CodeSaveRow("BE","변경",1,"Y",null,null,null,null);
        assertThat(sql("updateCode",new CodeWrite("JOB",row,Map.of("cd_nm","변경"),java.time.LocalDateTime.of(2026,10,22,9,0),null)))
                .contains("cd_nm=?","updated_at=?","updated_by=?").doesNotContain("attr1_val=","sort_seq=","use_yn=");
    }
    @Test void generatedKeyAndCompositeUpsertKeepSourceMetadata() {
        var insert=configuration.getMappedStatement("com.sicost.admin.AdminMapper.insertExcel"); assertThat(insert.getKeyProperties()).containsExactly("mapId");
        var write=new ParamWrite("VAT_RATE","2026-11","2000-01",new java.math.BigDecimal("11"),null,null);
        assertThat(sql("saveParam",write)).contains("oprt_std_desc","ON CONFLICT(oprt_std_id,aply_bgn_ym)","DO UPDATE SET num_val=EXCLUDED.num_val,txt_val=EXCLUDED.txt_val").doesNotContain("updated_at");
    }
}
