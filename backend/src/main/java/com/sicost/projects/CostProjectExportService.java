package com.sicost.projects;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.CurrentUser;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.projects.CostProjectModels.*;

@Service
public class CostProjectExportService {
    private final CostProjectService service;
    private final CostProjectMapper mapper;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final Clock clock;
    public CostProjectExportService(CostProjectService service,CostProjectMapper mapper,ExcelExportService excel,CurrentUser user,Clock clock) {
        this.service=service;this.mapper=mapper;this.excel=excel;this.user=user;this.clock=clock;
    }
    static List<ExportColumn<Row>> columns() {
        return List.of(new ExportColumn<>("prj_nm","프로젝트",false,false,Row::prj_nm),
            new ExportColumn<>("cntrct_ty_cd","유형",false,false,Row::cntrct_ty_cd),
            new ExportColumn<>("prj_stts_cd","상태",false,false,Row::prj_stts_cd),
            new ExportColumn<>("init_cntrct_amt","원 계약금액",false,true,Row::init_cntrct_amt),
            new ExportColumn<>("cur_cntrct_amt","현재 계약금액",false,true,Row::cur_cntrct_amt),
            new ExportColumn<>("plan_mm_qty","계획 MM",false,true,Row::plan_mm_qty),
            new ExportColumn<>("actual_mm_qty","실제 MM",false,true,Row::actual_mm_qty),
            new ExportColumn<>("confirmed_supply_amt","누적 매출",false,true,Row::confirmed_supply_amt),
            new ExportColumn<>("total_cost_amt","누적 원가",true,true,Row::total_cost_amt),
            new ExportColumn<>("profit_amt","이익",true,true,Row::profit_amt),
            new ExportColumn<>("margin_rt","누적 마진율",false,true,Row::margin_rt),
            new ExportColumn<>("expected_margin_rt","예상 최종 마진율",false,true,Row::expected_margin_rt),
            new ExportColumn<>("monthly_margin_rt","월간 마진율",false,true,Row::monthly_margin_rt),
            new ExportColumn<>("target_diff_rt","목표 대비",false,true,Row::target_diff_rt),
            new ExportColumn<>("budget_use_rt","예산 소진율",false,true,Row::budget_use_rt),
            new ExportColumn<>("per_mm_price_amt","MM당 단가",false,true,Row::per_mm_price_amt),
            new ExportColumn<>("per_mm_cost_amt","MM당 원가",true,true,Row::per_mm_cost_amt),
            new ExportColumn<>("per_mm_profit_amt","MM당 이익",true,true,Row::per_mm_profit_amt),
            new ExportColumn<>("free_mm_qty","무상지원 MM",false,true,Row::free_mm_qty),
            new ExportColumn<>("free_cost_amt","무상지원 원가",true,true,Row::free_cost_amt),
            new ExportColumn<>("main_cause","주원인",false,false,Row::main_cause));
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public byte[] download(Query query) {
        var result=service.exportRows(query);var columns=columns();
        byte[] bytes=excel.create("연도="+query.year()+", 유형="+Objects.toString(query.type(),"전체")+", 상태="+Objects.toString(query.status(),"전체")
            +", 파트="+Objects.toString(query.partId(),"전체")+", 검색="+Objects.toString(query.q(),""),user.name(),
            LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))),columns,result.items());
        String metadata="{\"rowCount\":"+result.total()+",\"columns\":["+columns.stream()
            .map(c->"{\"key\":\""+c.key()+"\",\"sensitive\":"+c.sensitive()+"}").collect(java.util.stream.Collectors.joining(","))+"]}";
        mapper.access(new Access("프로젝트원가분석_"+query.year()+".xlsx",metadata,user.userId()));
        return bytes;
    }
}
