package com.sicost.dispatchattendance2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.validation.BillingValues;
import com.sicost.uploadsops.*;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import com.sicost.uploadsops.PersonnelUploadModels.Result;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;
import static com.sicost.uploadsops.UploadModels.*;

@Service
public class DispatchAttendanceUploadService implements UploadReviewProvider {
    private final DispatchAttendanceUploadMapper mapper;
    private final DispatchAttendanceUploadForm form;
    private final UploadWorkbookWriter writer;
    private final UploadInspectionContexts contexts;
    private final Clock clock;
    private final TransactionTemplate transaction;
    @Autowired private UploadMapper uploads;
    @Autowired private ObjectMapper json;
    @Value("${sicost.upload-directory:${java.io.tmpdir}/sicost/uploads}") private String directory;
    private final Map<Long, Review> reviews = new HashMap<>();
    private static final class Review {
        final long id;
        final Scope scope;
        final Project project;
        final List<Assignment> assignments;
        final String name;
        final Path path;
        final byte[] digest;
        final Parsed parsed;
        final Instant expires;
        final List<Attendance> existing;
        Applied completed;
        Review(long id, Inspection inspection, Instant expires) {
            this.id = id; this.scope = inspection.scope(); this.project = inspection.project();
            this.assignments = inspection.assignments(); this.name = inspection.name();
            this.path = inspection.path(); this.digest = inspection.digest(); this.parsed = inspection.parsed();
            this.expires = expires; this.existing = inspection.existing();
        }
    }
    private record Inspection(Scope scope, Project project, List<Assignment> assignments, String name,
                              Path path, byte[] digest, Parsed parsed, List<Attendance> existing) {}

    public DispatchAttendanceUploadService(DispatchAttendanceUploadMapper mapper, DispatchAttendanceUploadForm form,
            UploadWorkbookWriter writer, UploadInspectionContexts contexts, Clock clock, PlatformTransactionManager manager) {
        this.mapper = mapper; this.form = form; this.writer = writer; this.contexts = contexts;
        this.clock = clock; this.transaction = new TransactionTemplate(manager);
    }
    public String formType() { return "B.3"; }
    public boolean owns(long id) {
        synchronized (reviews) { return reviews.containsKey(id); }
    }
    private Project project(Scope scope, boolean lock) {
        if (scope.projectId() < 1) throw BillingValues.invalid("projectId");
        BillingValues.month(scope.month(), "month");
        Project project = lock ? mapper.lockProject(scope.projectId()) : mapper.project(scope.projectId());
        if (project == null || !"Y".equals(project.enabled()) || !"DP".equals(project.type())
                || !("DAY".equals(project.settlement()) || "HOUR".equals(project.settlement()))) {
            throw new ApiException(400, "VALIDATION_ERROR", "일·시간 단가 파견 프로젝트만 올릴 수 있습니다.", "projectId");
        }
        if ("CLOSED".equals(mapper.monthStatus(scope.month()))) {
            throw ApiException.conflict("CLOSED_MONTH", "마감된 달은 반영할 수 없습니다.", "month");
        }
        if (mapper.confirmedUsage(scope)) throw ApiException.conflict("ATTENDANCE_BILLED",
            "확정 청구에 사용한 근태가 있어 선택 범위를 교체할 수 없습니다.", "month");
        return project;
    }
    public Result validate(Scope scope, MultipartFile file) {
        BillingValues.month(scope.month(), "month");
        if (file == null || file.isEmpty() || file.getSize() > 10L * 1024 * 1024) {
            throw new ApiException(400, "VALIDATION_ERROR", "비어 있지 않은 10MB 이하 xlsx 파일을 선택하세요.", "file");
        }
        String name = file.getOriginalFilename();
        if (name == null) throw BillingValues.invalid("file");
        name = name.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}<>:\"|?*]", "_");
        if (name.length() > 255 || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw BillingValues.invalid("file");
        final String filename = name;
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (IOException ex) { throw storageError(); }
        Path path = Path.of(directory).toAbsolutePath().normalize().resolve(UUID.randomUUID() + ".xlsx");
        Instant validatedAt = clock.instant();
        try {
            Review review = Objects.requireNonNull(transaction.execute(status -> {
                Project project = project(scope, false);
                List<Assignment> assignments = mapper.assignments(scope);
                Parsed parsed = form.parse(new ByteArrayInputStream(bytes), scope, project, assignments);
                try { Files.createDirectories(path.getParent()); Files.write(path, bytes, StandardOpenOption.CREATE_NEW); }
                catch (IOException ex) { throw storageError(); }
                Review r = new Review(mapper.nextUploadId(), new Inspection(scope, project, assignments, filename,
                    path, digest(bytes), parsed, mapper.attendance(scope)), validatedAt.plusSeconds(1800));
                Result result = result(r);
                if (result.error_count() > 0) {
                    mapper.insertReview(new ReviewWrite(r.id, scope, filename, path.toString(), result, "REJECTED"));
                    saveIssues(r);
                    mapper.access(new UploadAccess("UPLOAD", filename.substring(0, Math.min(200, filename.length())),
                        snapshot(Map.of("upload_id", r.id, "status", "REJECTED")), null));
                }
                return r;
            }));
            Result result = result(review);
            contexts.publish(new UploadInspectionContexts.Inspection(review.id, "B.3", scope.month(),
                result.row_count(), result.error_count(), result.warning_count(), result.can_apply(), true), validatedAt);
            synchronized (reviews) {
                reviews.entrySet().removeIf(e -> !clock.instant().isBefore(e.getValue().expires));
                reviews.put(review.id, review);
            }
            return result;
        } catch (RuntimeException ex) {
            try { Files.deleteIfExists(path); } catch (IOException ignored) { /* Preserve original failure. */ }
            throw ex;
        }
    }
    private Review review(long id) {
        if (id < 1) throw BillingValues.invalid("upload_id");
        Review review;
        synchronized (reviews) { review = reviews.get(id); }
        if (review == null || !clock.instant().isBefore(review.expires)) throw new ApiException(410,
            "VALIDATION_EXPIRED", "검사 후 30분이 지났습니다. 다시 검사하세요.", "upload_id");
        return review;
    }
    private Result result(Review r) {
        int errors = (int) r.parsed.rows().stream().filter(p -> p.severity().equals("ERROR")).count();
        int warnings = (int) r.parsed.rows().stream().filter(p -> p.severity().equals("WARNING")).count();
        return new Result(r.id, "B.3", r.scope.month(), r.parsed.rows().size(), errors, warnings,
            errors == 0 && r.completed == null, 0,
            r.scope.month() + " 선택 프로젝트 근태 " + r.existing.size() + "건을 파일 전체로 교체합니다. 파일에서 빠진 인력은 삭제됩니다.",
            "/api/uploads/" + r.id + "/issues", true);
    }
    public Applied apply(ApplyInput input) {
        if (!input.confirm()) throw BillingValues.invalid("confirm");
        Review r = review(input.upload_id());
        synchronized (r) {
            if (r.completed != null) return r.completed;
            if (!result(r).can_apply() || !contexts.require(r.id).canApply()) {
                throw new ApiException(400, "VALIDATION_ERROR", "오류가 있어 파일 전체를 반영하지 않습니다.", "upload_id");
            }
            Applied applied = transaction.execute(status -> {
                Project currentProject = project(r.scope, true);
                if (!clock.instant().isBefore(r.expires)) throw new ApiException(410,
                    "VALIDATION_EXPIRED", "검사 결과가 만료되었습니다. 다시 검사하세요.", "upload_id");
                byte[] bytes;
                try { bytes = Files.readAllBytes(r.path); } catch (IOException ex) { throw stale(); }
                if (!MessageDigest.isEqual(r.digest, digest(bytes))) throw stale();
                List<Assignment> currentAssignments = mapper.assignments(r.scope);
                Parsed current = form.parse(new ByteArrayInputStream(bytes), r.scope, currentProject, currentAssignments);
                if (!MessageDigest.isEqual(r.digest, digest(bytes)) || !current.equals(r.parsed)
                        || !currentProject.equals(r.project) || !currentAssignments.equals(r.assignments)) throw stale();
                List<Attendance> before = mapper.attendance(r.scope);
                if (!before.equals(r.existing)) throw stale();
                mapper.insertReview(new ReviewWrite(r.id, r.scope, r.name, r.path.toString(), result(r), "APPLIED"));
                mapper.deleteAttendance(r.scope);
                for (Attendance old : before) mapper.audit(new Audit(Long.toString(old.atnd_id()), "DELETE",
                    snapshot(Map.of("scope", r.scope, "attendance", old)), null));
                for (Preview row : current.rows()) {
                    Long id = mapper.insertAttendance(new AttendanceWrite(r.scope, row.attendance(), r.id));
                    mapper.audit(new Audit(Long.toString(Objects.requireNonNull(id)), "INSERT", null,
                        snapshot(Map.of("scope", r.scope, "attendance", row.attendance(), "upload_id", r.id))));
                }
                saveIssues(r);
                mapper.access(new UploadAccess("UPLOAD", r.name.substring(0, Math.min(200, r.name.length())),
                    snapshot(Map.of("upload_id", r.id, "applied_count", current.rows().size())), null));
                return new Applied(r.id, "B.3", current.rows().size(), "선택 월·프로젝트 근태를 반영했습니다. 연결 작성 청구는 다시 계산 전까지 유지됩니다.");
            });
            r.completed = Objects.requireNonNull(applied);
            contexts.invalidate(r.id);
            return applied;
        }
    }
    private void saveIssues(Review r) {
        for (Issue i : r.parsed.issues()) {
            String raw = i.raw_value();
            uploads.insertIssue(new IssueWrite(r.id, new UploadIssue(i.severity().equals("WARNING") ? "WARN" : "ERROR",
                i.row(), i.column(), raw, i.message(), null), raw == null ? null : raw.substring(0, Math.min(500, raw.length()))));
        }
    }
    public PageResult<Issue> issues(long id, String severity, String column, DeveloperPaging paging) {
        Review r = review(id);
        if (severity != null && !Set.of("ERROR", "WARNING").contains(severity)) throw BillingValues.invalid("severity");
        if (column != null && !DispatchAttendanceUploadForm.HEADERS.contains(column) && !column.equals("파일")) {
            throw BillingValues.invalid("column");
        }
        List<Issue> filtered = r.parsed.issues().stream().filter(i -> severity == null || severity.equals(i.severity()))
            .filter(i -> column == null || column.equals(i.column())).sorted(order(paging, Issue::row)).toList();
        return page(filtered, paging);
    }
    public PageResult<Preview> rows(long id, String severity, DeveloperPaging paging) {
        Review r = review(id);
        if (severity != null && !Set.of("ERROR", "WARNING", "NORMAL").contains(severity)) throw BillingValues.invalid("severity");
        return page(r.parsed.rows().stream().filter(p -> severity == null || severity.equals(p.severity()))
            .sorted(order(paging, Preview::row)).toList(), paging);
    }
    private static <T> Comparator<T> order(DeveloperPaging paging, java.util.function.ToIntFunction<T> row) {
        Comparator<T> order = Comparator.comparingInt(row);
        if ("row,desc".equals(paging.sort())) return order.reversed();
        if (paging.sort() != null && !"row,asc".equals(paging.sort())) throw BillingValues.invalid("sort");
        return order;
    }
    private static <T> PageResult<T> page(List<T> rows, DeveloperPaging paging) {
        int from = (int) Math.min((long) paging.page() * paging.size(), rows.size());
        return paging.result(rows.subList(from, Math.min(from + paging.size(), rows.size())), rows.size());
    }
    public UploadFile errors(long id) {
        Review r = review(id);
        List<UploadIssue> issues = r.parsed.issues().stream().map(i -> new UploadIssue(
            i.severity().equals("WARNING") ? "WARN" : "ERROR", i.row(), i.column(), i.raw_value(), i.message(), null)).toList();
        try (InputStream input = Files.newInputStream(r.path)) {
            return new UploadFile(r.name.replaceFirst("(?i)\\.xlsx$", "") + "_오류.xlsx",
                writer.errorFile(input, issues, DispatchAttendanceUploadForm.HEADERS));
        } catch (IOException ex) { throw storageError(); }
    }
    public UploadFile template() {
        try { return new UploadFile("파견근태_업로드양식.xlsx", writer.template(DispatchAttendanceUploadForm.HEADERS, "파견 근태")); }
        catch (IOException ex) { throw storageError(); }
    }
    private String snapshot(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("근태 이력 저장 실패", ex); }
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static ApiException stale() {
        return ApiException.conflict("STALE_VALIDATION", "검사 결과 또는 기준정보가 변경되었습니다. 다시 검사하세요.", "upload_id");
    }
    private static ApiException storageError() {
        return new ApiException(500, "SERVER_ERROR", "업로드 파일을 처리할 수 없습니다.", "file");
    }
}
