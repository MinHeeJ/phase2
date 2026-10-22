INSERT INTO fin.mth_cls (cls_ym, cls_stts_cd, cls_at)
SELECT
    to_char(m, 'YYYY-MM') AS cls_ym,
    'CLOSED' AS cls_stts_cd,
    m + interval '1 month 4 days' AS cls_at
FROM
    generate_series(
        timestamp '2026-01-01', timestamp '2026-09-01', interval '1 month'
    ) AS m
ON CONFLICT (cls_ym) DO NOTHING;
INSERT INTO fin.mth_cls (cls_ym, cls_stts_cd)
VALUES ('2026-10', 'OPEN')
ON CONFLICT (cls_ym) DO NOTHING;
