package com.sicost.costsops;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.costsops.RenegotiationModels.*;

@Service
public class RenegotiationExportService {
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final RenegotiationService service;
    private final ExcelExportService excel;
    private final RenegotiationMapper mapper;
    private final CurrentUser user;
    private final Clock clock;

    public RenegotiationExportService(RenegotiationService service, ExcelExportService excel,
            RenegotiationMapper mapper, CurrentUser user, Clock clock) {
        this.service = service;
        this.excel = excel;
        this.mapper = mapper;
        this.user = user;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ResponseEntity<byte[]> download(Query query) {
        var result = service.list(query, true);
        var columns = List.<ExportColumn<Row>>of(
            new ExportColumn<>("dev_nm", "이름", false, false, Row::dev_nm),
            new ExportColumn<>("emp_se_cd", "직원구분", false, false, Row::emp_se_cd),
            new ExportColumn<>("prj_nm", "프로젝트", false, false, Row::prj_nm),
            new ExportColumn<>("end_dt", "갱신일", false, false, Row::end_dt),
            new ExportColumn<>("days_remaining", "남은 일수", false, true, Row::days_remaining),
            new ExportColumn<>("mm_qty", "MM", false, true, Row::mm_qty),
            new ExportColumn<>("bill_untprc_amt", "현재 단가", true, true, Row::bill_untprc_amt),
            new ExportColumn<>("allocated_cost_amt", "월 배분원가", true, true, Row::allocated_cost_amt),
            new ExportColumn<>("margin_rt", "이익률(%)", true, true, Row::margin_rt),
            new ExportColumn<>("target_price_amt", "목표 단가", true, true, Row::target_price_amt),
            new ExportColumn<>("required_raise_rt", "필요 인상률(%)", true, true, Row::required_raise_rt),
            new ExportColumn<>("assumed_annual_profit_increase_amt", "가정 연 증가", true, true, Row::assumed_annual_profit_increase_amt),
            new ExportColumn<>("target_annual_profit_increase_amt", "목표 연 증가", true, true, Row::target_annual_profit_increase_amt));
        String conditions = "기준월=" + result.calculation_month() + "; " + query.filters()
            + "; sort=" + query.paging().sort() + "; 적용 인상률=" + result.raise_rate()
            + "; 목표 이익률=" + result.target_margin_rate() + "; 12개월 유지 가정 (실제 계약 보장 아님)";
        byte[] bytes = excel.create(conditions, user.name(),
            LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))), columns, result.items());
        String name = "단가재협상.xlsx";
        mapper.download(new Download(name, "{\"rows\":" + result.total() + "}", user.userId()));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(XLSX))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
            .body(bytes);
    }
}
