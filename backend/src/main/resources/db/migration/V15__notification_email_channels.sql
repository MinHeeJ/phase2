-- Required channel configuration is data, not an in-memory response fallback.
-- Existing administrator choices and effective-dated revisions are preserved.
INSERT INTO cmn.oprt_std(oprt_std_id,aply_bgn_ym,oprt_std_nm,txt_val,use_plc_desc)
SELECT 'NOTIFICATION_EMAIL_' || event_type,'2000-01',event_type,'N','업무 알림 선택 이메일 채널'
FROM (VALUES ('DUNNING'),('RISK_PROJECT'),('WITHDRAW'),('CASH_SHORT'),('LOW_MARGIN'),
    ('QUOTE_APPROVAL'),('UPLOAD'),('AI_RECOMMEND'),('MONTH_CLOSE_MISSING'),('STAFF_SHORTAGE')) AS events(event_type)
WHERE NOT EXISTS(SELECT 1 FROM cmn.oprt_std s WHERE s.oprt_std_id='NOTIFICATION_EMAIL_' || event_type)
ON CONFLICT DO NOTHING;
