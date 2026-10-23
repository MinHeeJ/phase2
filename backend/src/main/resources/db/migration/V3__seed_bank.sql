INSERT INTO cmn.cd_dtl (cd_grp_id, dtl_cd, cd_nm, sort_seq) VALUES
('BANK', 'KB', '국민은행', 1),
('BANK', 'SHINHAN', '신한은행', 2),
('BANK', 'WOORI', '우리은행', 3),
('BANK', 'HANA', '하나은행', 4),
('BANK', 'NH', 'NH농협은행', 5),
('BANK', 'IBK', 'IBK기업은행', 6),
('BANK', 'SC', 'SC제일은행', 7),
('BANK', 'KAKAO', '카카오뱅크', 8),
('BANK', 'TOSS', '토스뱅크', 9),
('BANK', 'KBANK', '케이뱅크', 10)
ON CONFLICT (cd_grp_id, dtl_cd) DO NOTHING;
