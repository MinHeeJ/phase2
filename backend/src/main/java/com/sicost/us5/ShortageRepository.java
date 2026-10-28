package com.sicost.us5;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.sicost.us5.ShortageModels.*;

@Repository
public class ShortageRepository {
    private final JdbcTemplate jdbc;
    public ShortageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Row> find(Filter filter, String effectiveMonth) {
        StringBuilder sql = new StringBuilder("""
            WITH plans AS (
              SELECT prj_id, job_cd, plan_ym, sum(need_mm_qty) AS plan_mm
              FROM prj.psn_plan WHERE plan_ym BETWEEN ? AND ? GROUP BY prj_id, job_cd, plan_ym
            ), actuals AS (
              SELECT a.prj_id, a.job_cd, m.work_ym, sum(m.mm_qty) AS actual_mm
              FROM asgn.asgn a JOIN asgn.asgn_mth m ON m.asgn_id = a.asgn_id
              WHERE a.asgn_stts_cd <> 'CANCELLED' AND m.work_ym BETWEEN ? AND ?
              GROUP BY a.prj_id, a.job_cd, m.work_ym
            )
            SELECT p.prj_id, p.prj_nm, pl.job_cd, coalesce(c.cd_nm, pl.job_cd) AS job_name,
                   pl.plan_ym, pl.plan_mm, coalesce(a.actual_mm, 0) AS actual_mm,
                   pl.plan_mm - coalesce(a.actual_mm, 0) AS shortage_mm
            FROM plans pl JOIN prj.prj p ON p.prj_id = pl.prj_id
            LEFT JOIN actuals a ON a.prj_id = pl.prj_id AND a.job_cd = pl.job_cd AND a.work_ym = pl.plan_ym
            LEFT JOIN cmn.cd_dtl c ON c.cd_grp_id = 'JOB' AND c.dtl_cd = pl.job_cd
            WHERE p.use_yn = 'Y' AND p.prj_stts_cd <> 'LOST'
              AND p.prfm_bgn_dt <= ? AND p.cur_end_dt >= ?
              AND pl.plan_mm - coalesce(a.actual_mm, 0) > (
                SELECT num_val FROM cmn.oprt_std WHERE oprt_std_id = 'PLAN_GAP_TOLERANCE'
                  AND aply_bgn_ym <= ? ORDER BY aply_bgn_ym DESC LIMIT 1)
            """);
        String first = filter.year() + "-01", last = filter.year() + "-12";
        var args = new ArrayList<Object>(List.of(first, last, first, last,
            java.sql.Date.valueOf(filter.year() + "-12-31"), java.sql.Date.valueOf(filter.year() + "-01-01"), effectiveMonth));
        if (filter.projectId() != null) { sql.append(" AND p.prj_id = ?"); args.add(filter.projectId()); }
        if (filter.job() != null) { sql.append(" AND pl.job_cd = ?"); args.add(filter.job()); }
        sql.append(" ORDER BY shortage_mm DESC, pl.plan_ym, p.prj_nm, p.prj_id, pl.job_cd");
        return jdbc.query(sql.toString(), (rs, n) -> new Row(rs.getLong("prj_id"), rs.getString("prj_nm"),
            rs.getString("job_cd"), rs.getString("job_name"), rs.getString("plan_ym"),
            rs.getBigDecimal("plan_mm"), rs.getBigDecimal("actual_mm"), rs.getBigDecimal("shortage_mm")), args.toArray());
    }
}
