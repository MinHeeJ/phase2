-- 거래처 지급 계좌와 B.7 업로드에서 사용하는 은행 선택 코드.
INSERT INTO cmn.cd_dtl (cd_grp_id, dtl_cd, cd_nm, sort_seq, use_yn) VALUES
 ('BANK', 'KB', '국민은행', 1, 'Y'),
 ('BANK', 'SHINHAN', '신한은행', 2, 'Y'),
 ('BANK', 'WOORI', '우리은행', 3, 'Y'),
 ('BANK', 'HANA', '하나은행', 4, 'Y'),
 ('BANK', 'NH', 'NH농협은행', 5, 'Y'),
 ('BANK', 'IBK', 'IBK기업은행', 6, 'Y'),
 ('BANK', 'SC', 'SC제일은행', 7, 'Y'),
 ('BANK', 'KAKAO', '카카오뱅크', 8, 'Y'),
 ('BANK', 'TOSS', '토스뱅크', 9, 'Y'),
 ('BANK', 'KBANK', '케이뱅크', 10, 'Y')
ON CONFLICT (cd_grp_id, dtl_cd) DO NOTHING;
