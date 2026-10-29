package com.sicost.costsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.costsops.RenegotiationModels.*;

@Service
public class RenegotiationExportService {
    private final RenegotiationService service;
    private final RenegotiationMapper mapper;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final ObjectMapper json;
    public RenegotiationExportService(RenegotiationService service, RenegotiationMapper mapper,
            ExcelExportService excel, CurrentUser user, PermissionService permissions, ObjectMapper json) {
        this.service = service;
        this.mapper = mapper;
        this.excel = excel;
        this.user = user;
        this.permissions = permissions;
        this.json = json;
    }
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Download export(Map<String, String> input) {
        permissions.can(CostAnalysisActions.RENEGOTIATIONS_DOWNLOAD);
        var query = RenegotiationInput.parse(input);
        var result = service.query(query, true);
        List<ExportColumn<Row>> columns = List.of(
            new ExportColumn<>("dev_nm", "이름", false, false, Row::dev_nm),
            new ExportColumn<>("emp_se_cd", "구분", false, false, Row::emp_se_cd),
            new ExportColumn<>("prj_nm", "프로젝트", false, false, Row::prj_nm),
            new ExportColumn<>("end_dt", "갱신일", false, false, Row::end_dt),
            new ExportColumn<>("mm_qty", "MM", false, true, Row::mm_qty),
            new ExportColumn<>("bill_untprc_amt", "현재 단가", false, true, Row::bill_untprc_amt),
            new ExportColumn<>("monthly_cost_amt", "월 원가", true, true, Row::monthly_cost_amt),
            new ExportColumn<>("margin_rt", "이익률", true, true, Row::margin_rt),
            new ExportColumn<>("target_price_amt", "목표 단가 (20%)", true, true, Row::target_price_amt),
            new ExportColumn<>("required_raise_rt", "필요 인상률", true, true, Row::required_raise_rt),
            new ExportColumn<>("annual_profit_increase_amt", "가정 연 이익 증가", true, true, Row::annual_profit_increase_amt),
            new ExportColumn<>("target_annual_profit_increase_amt", "목표 연 이익 증가", true, true, Row::target_annual_profit_increase_amt));
        String filename = "단가재협상_" + result.month() + ".xlsx";
        byte[] bytes = excel.create("갱신 " + query.windowMonths() + "개월 · 이익률 " + query.marginBelow()
            + "% 미만 · 인상률 " + query.raiseRate() + "% · 기준월 " + result.referenceMonth(), user.name(),
            LocalDateTime.now(ZoneId.of("Asia/Seoul")), columns, result.items());
        try {
            mapper.download(new Access(filename, json.writeValueAsString(Map.of("filename", filename,
                "conditions", query, "columns", columns.stream().map(ExportColumn::key).toList())), user.userId()));
        } catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
        return new Download(filename, bytes);
    }
}
