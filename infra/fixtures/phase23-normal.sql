-- Run only in a newly created, isolated phase-23 normal-preview database.
-- V2 contains illustrative CLOSED rows without CLOSE snapshots or history.
-- Remove those illustrative rows here; the application will create a real
-- October CLOSE through its public API after the prerequisite data is saved.
DO $$
BEGIN
  IF (SELECT count(*) FROM fin.mth_cls) != 10
     OR (SELECT count(*) FROM fin.mth_cls WHERE cls_ym BETWEEN '2026-01' AND '2026-09' AND cls_stts_cd='CLOSED') != 9
     OR (SELECT count(*) FROM fin.mth_cls WHERE cls_ym='2026-10' AND cls_stts_cd='OPEN') != 1
     OR EXISTS (SELECT 1 FROM fin.mth_cls_hist)
     OR EXISTS (SELECT 1 FROM fin.mth_pl WHERE pl_src_cd='CLOSE')
     OR EXISTS (SELECT 1 FROM fin.corp_tax_mth)
  THEN
    RAISE EXCEPTION 'phase23-normal.sql requires an untouched, isolated V2/V7 seed database';
  END IF;
END $$;

DELETE FROM fin.mth_cls WHERE cls_ym BETWEEN '2026-01' AND '2026-09';
