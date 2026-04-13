-- Run this after the application has started and the SUPERADMIN already exists.
-- Password for every seeded ADMIN: Welcome@123

INSERT INTO schools (name, short_code, city, state, contact_email, contact_phone, active, created_at) VALUES
('Delhi Public School, Miyapur', 'DPS-MYP', 'Hyderabad', 'Telangana', 'principal@dpsmiyapur.edu.in', '9876543210', true, NOW()),
('National Public School, Koramangala', 'NPS-KOR', 'Bengaluru', 'Karnataka', 'principal@npskoramangala.edu.in', '9876543211', true, NOW()),
('Smt. Sulochanadevi Singhania School', 'SSS-THN', 'Mumbai', 'Maharashtra', 'office@singhaniaschool.org', '9876543212', true, NOW()),
('Chettinad Vidyashram', 'CV-CHN', 'Chennai', 'Tamil Nadu', 'principal@chettinadvidyashram.org', '9876543213', true, NOW()),
('Modern School, Barakhamba Road', 'MS-DEL', 'Delhi', 'Delhi', 'principal@modernschool.net', '9876543214', true, NOW()),
('The Bishop's School, Camp', 'TBS-PUN', 'Pune', 'Maharashtra', 'principal@thebishopsschool.org', '9876543215', true, NOW()),
('St. Xavier's Collegiate School', 'SXC-KOL', 'Kolkata', 'West Bengal', 'principal@sxcs.edu.in', '9876543216', true, NOW()),
('City Montessori School, Gomti Nagar', 'CMS-LKO', 'Lucknow', 'Uttar Pradesh', 'principal@cmsgn.edu.in', '9876543217', true, NOW()),
('Mayo College Girls' School', 'MCGS-AJM', 'Ajmer', 'Rajasthan', 'principal@mcgs.ac.in', '9876543218', true, NOW()),
('Navrachana International School', 'NIS-VAD', 'Vadodara', 'Gujarat', 'principal@navrachana.edu.in', '9876543219', true, NOW()),
('ODM Public School', 'ODM-BBS', 'Bhubaneswar', 'Odisha', 'principal@odmps.org', '9876543220', true, NOW()),
('The Heritage School', 'THS-GGN', 'Gurugram', 'Haryana', 'principal@heritageschoolggn.com', '9876543221', true, NOW()),
('Delhi Public School, Bopal', 'DPS-BPL', 'Ahmedabad', 'Gujarat', 'principal@dpsbopal-ahd.edu.in', '9876543222', true, NOW()),
('Oakridge International School', 'OIS-VSK', 'Visakhapatnam', 'Andhra Pradesh', 'principal@oakridge.in', '9876543223', true, NOW()),
('St. Kabir School', 'SKS-SRT', 'Surat', 'Gujarat', 'principal@stkabirschool.com', '9876543224', true, NOW());

INSERT INTO app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
SELECT 'School Admin - DPS Miyapur', 'admin@dps-myp.custoking.com', SHA2(CONCAT('Welcome@123', ''), 256), 'ADMIN', id, name, NOW() FROM schools WHERE short_code = 'DPS-MYP';
INSERT INTO app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
SELECT 'School Admin - NPS Koramangala', 'admin@nps-kor.custoking.com', SHA2(CONCAT('Welcome@123', ''), 256), 'ADMIN', id, name, NOW() FROM schools WHERE short_code = 'NPS-KOR';
INSERT INTO app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
SELECT 'School Admin - Singhania Thane', 'admin@sss-thn.custoking.com', SHA2(CONCAT('Welcome@123', ''), 256), 'ADMIN', id, name, NOW() FROM schools WHERE short_code = 'SSS-THN';
INSERT INTO app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
SELECT 'School Admin - Chettinad', 'admin@cv-chn.custoking.com', SHA2(CONCAT('Welcome@123', ''), 256), 'ADMIN', id, name, NOW() FROM schools WHERE short_code = 'CV-CHN';
INSERT INTO app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
SELECT 'School Admin - Modern Delhi', 'admin@ms-del.custoking.com', SHA2(CONCAT('Welcome@123', ''), 256), 'ADMIN', id, name, NOW() FROM schools WHERE short_code = 'MS-DEL';
INSERT INTO app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
SELECT 'School Admin - Bishop Pune', 'admin@tbs-pun.custoking.com', SHA2(CONCAT('Welcome@123', ''), 256), 'ADMIN', id, name, NOW() FROM schools WHERE short_code = 'TBS-PUN';
INSERT INTO app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
SELECT 'School Admin - Xavier Kolkata', 'admin@sxc-kol.custoking.com', SHA2(CONCAT('Welcome@123', ''), 256), 'ADMIN', id, name, NOW() FROM schools WHERE short_code = 'SXC-KOL';
INSERT INTO app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
SELECT 'School Admin - CMS Lucknow', 'admin@cms-lko.custoking.com', SHA2(CONCAT('Welcome@123', ''), 256), 'ADMIN', id, name, NOW() FROM schools WHERE short_code = 'CMS-LKO';
INSERT INTO app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
SELECT 'School Admin - Mayo Ajmer', 'admin@mcgs-ajm.custoking.com', SHA2(CONCAT('Welcome@123', ''), 256), 'ADMIN', id, name, NOW() FROM schools WHERE short_code = 'MCGS-AJM';
INSERT INTO app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
SELECT 'School Admin - Navrachana Vadodara', 'admin@nis-vad.custoking.com', SHA2(CONCAT('Welcome@123', ''), 256), 'ADMIN', id, name, NOW() FROM schools WHERE short_code = 'NIS-VAD';
INSERT INTO app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
SELECT 'School Admin - ODM Bhubaneswar', 'admin@odm-bbs.custoking.com', SHA2(CONCAT('Welcome@123', ''), 256), 'ADMIN', id, name, NOW() FROM schools WHERE short_code = 'ODM-BBS';
INSERT INTO app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
SELECT 'School Admin - Heritage Gurugram', 'admin@ths-ggn.custoking.com', SHA2(CONCAT('Welcome@123', ''), 256), 'ADMIN', id, name, NOW() FROM schools WHERE short_code = 'THS-GGN';
INSERT INTO app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
SELECT 'School Admin - DPS Bopal', 'admin@dps-bpl.custoking.com', SHA2(CONCAT('Welcome@123', ''), 256), 'ADMIN', id, name, NOW() FROM schools WHERE short_code = 'DPS-BPL';
INSERT INTO app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
SELECT 'School Admin - Oakridge Vizag', 'admin@ois-vsk.custoking.com', SHA2(CONCAT('Welcome@123', ''), 256), 'ADMIN', id, name, NOW() FROM schools WHERE short_code = 'OIS-VSK';
INSERT INTO app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at)
SELECT 'School Admin - St Kabir Surat', 'admin@sks-srt.custoking.com', SHA2(CONCAT('Welcome@123', ''), 256), 'ADMIN', id, name, NOW() FROM schools WHERE short_code = 'SKS-SRT';
