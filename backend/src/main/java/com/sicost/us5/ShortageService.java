package com.sicost.us5;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.api.DownloadArtifact;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.CurrentUser;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.us5.ShortageModels.*;

@Service
public class ShortageService {
    private final ShortageRepository repository;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ShortageService(ShortageRepository repository, ExcelExportService excel, CurrentUser user,
                           Clock clock, JdbcTemplate jdbc, ObjectMapper json) {
        this.repository = repository; this.excel = excel; this.user = user;
        this.clock = clock; this.jdbc = jdbc; this.json = json;
    }
    public Filter parse(Map<String,String> query) {
        for (String key : query.keySet()) if (!Set.of("year", "projectId", "job", "page", "size").contains(key))
            throw ApiException.badRequest("지원하지 않는 조회 조건입니다.", key);
        int year = number(query.get("year"), "year", LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).getYear(), 1, 9999);
        int page = number(query.get("page"), "page", 1, 1, 1000000);
        int size = number(query.get("size"), "size", 50, 1, 50);
        Long project = null;
        if (query.containsKey("projectId")) {
            try { project = Long.valueOf(query.get("projectId")); if (project < 1) throw new NumberFormatException(); }
            catch (NumberFormatException e) { throw ApiException.badRequest("프로젝트를 확인하세요.", "projectId"); }
        }
        String job = query.get("job");
        if (job != null && !job.matches("[A-Za-z0-9_]{1,30}")) throw ApiException.badRequest("직무를 확인하세요.", "job");
        return new Filter(year, project, job, page, size);
    }
    private int number(String text, String field, int fallback, int min, int max) {
        if (text == null) return fallback;
        try { int n = Integer.parseInt(text); if (n < min || n > max) throw new NumberFormatException(); return n; }
        catch (NumberFormatException ex) { throw ApiException.badRequest("조회 조건을 확인하세요.", field); }
    }
    private String effectiveMonth() {
        return YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString();
    }
    @Transactional(readOnly = true)
    public Page list(Filter filter) {
        var rows = repository.find(filter, effectiveMonth());
        int start = (int)Math.min((long)(filter.page() - 1) * filter.size(), rows.size());
        return new Page(rows.subList(start, Math.min(start + filter.size(), rows.size())), rows.size(), filter.page(), filter.size());
    }
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public DownloadArtifact export(Filter filter) {
        var rows = repository.find(filter, effectiveMonth());
        var columns = List.of(
            new ExportColumn<Row>("projectName", "프로젝트", false, false, Row::projectName),
            new ExportColumn<Row>("jobName", "직무", false, false, Row::jobName),
            new ExportColumn<Row>("month", "월", false, false, Row::month),
            new ExportColumn<Row>("planMm", "계획 MM", false, false, r -> r.planMm().toPlainString()),
            new ExportColumn<Row>("actualMm", "실제·예정 MM", false, false, r -> r.actualMm().toPlainString()),
            new ExportColumn<Row>("shortageMm", "부족", false, false, r -> "부족 " + r.shortageMm().toPlainString()));
        var now = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        String filename = "미충원직무_" + filter.year() + "_" + now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
        String conditions = "연도=" + filter.year() + ", 프로젝트=" + Objects.toString(filter.projectId(), "전체") + ", 직무=" + Objects.toString(filter.job(), "전체");
        byte[] bytes = excel.create(conditions, user.name(), now, columns, rows);
        try {
            String detail = json.writeValueAsString(Map.of("filename", filename, "rowCount", rows.size(), "columns", columns.stream().map(ExportColumn::key).toList()));
            jdbc.update("INSERT INTO sys.acs_hist(user_id,acs_ty_cd,scrn_id,obj_desc,dtl_desc) VALUES(?,'DOWNLOAD','UI-11',?,?)", user.userId(), filename, detail);
        } catch (JsonProcessingException ex) { throw new IllegalStateException("다운로드 기록을 만들 수 없습니다.", ex); }
        return DownloadArtifact.workbook(filename, bytes);
    }
}
