package com.sicost.parts;

import com.sicost.common.costallocation.CostAnalysisInput;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.parts.PartSettlementModels.*;

@Service
public class PartSettlementExportService {
    private final PartSettlementMapper mapper;
    private final PartSettlementService settlements;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public PartSettlementExportService(PartSettlementMapper mapper, PartSettlementService settlements,
        ExcelExportService excel, CurrentUser user, PermissionService permissions, Clock clock) {
        this.mapper = mapper;
        this.settlements = settlements;
        this.excel = excel;
        this.user = user;
        this.permissions = permissions;
        this.clock = clock;
    }
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public File export(Map<String, String> input) {
        permissions.can(CostAnalysisActions.SETTLEMENTS_DOWNLOAD);
        String month = CostAnalysisInput.month(input.get("month"));
        String sort = input.getOrDefault("sort", "bsn_part_nm,asc");
        var order = PartSettlementService.order(sort);
        var rows = settlements.rows(month);
        rows.sort(order);
        List<ExportColumn<Row>> columns = List.of(
            new ExportColumn<>("bsn_part_nm", "파트", false, false, Row::bsn_part_nm),
            new ExportColumn<>("ldr_nm", "파트장", false, false, Row::ldr_nm),
            new ExportColumn<>("prft_dstrb_rt", "배분율", false, true, Row::prft_dstrb_rt),
            new ExportColumn<>("psn_cnt", "인원", false, true, Row::psn_cnt),
            new ExportColumn<>("tot_diff_amt", "차액 합계", true, true, Row::tot_diff_amt),
            new ExportColumn<>("tot_psn_prft_amt", "개인 이익금 합", true, true, Row::tot_psn_prft_amt),
            new ExportColumn<>("corp_card_amt", "법인카드", true, true, Row::corp_card_amt),
            new ExportColumn<>("part_prft_amt", "파트 이익금", true, true, Row::part_prft_amt),
            new ExportColumn<>("stlm_stts_cd", "정산 상태", false, false, Row::stlm_stts_cd),
            new ExportColumn<>("provisional", "잠정", false, false, Row::provisional));
        var now = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        String filename = "영업파트이익금_" + month + "_" + now.format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
        byte[] bytes = excel.create("조회월 " + month, user.name(), now, columns, rows);
        mapper.access(new Access(filename, "{\"month\":\"" + month + "\",\"columns\":[\"bsn_part_nm\",\"ldr_nm\",\"prft_dstrb_rt\",\"psn_cnt\",\"tot_diff_amt\",\"tot_psn_prft_amt\",\"corp_card_amt\",\"part_prft_amt\",\"stlm_stts_cd\",\"provisional\"]}", user.userId()));
        return new File(filename, bytes);
    }
}
