-- Fulfillment product master catalog.
-- Product capacity belongs to storage_location. Product weight and volume are
-- stored here so location-specific capacity can be derived when needed.

ALTER TABLE public.product ADD COLUMN IF NOT EXISTS category varchar(50);
ALTER TABLE public.product ADD COLUMN IF NOT EXISTS unit varchar(20);
ALTER TABLE public.product ADD COLUMN IF NOT EXISTS unit_weight_kg numeric(10,3);
ALTER TABLE public.product ADD COLUMN IF NOT EXISTS unit_volume_liter numeric(10,3);
ALTER TABLE public.product ADD COLUMN IF NOT EXISTS barcode varchar(32);
ALTER TABLE public.product ADD COLUMN IF NOT EXISTS temperature_zone varchar(20);
ALTER TABLE public.product ADD COLUMN IF NOT EXISTS expiry_managed boolean;
ALTER TABLE public.product ADD COLUMN IF NOT EXISTS fragile boolean;

INSERT INTO public.product (
    product_id, product_code, product_name, category, unit,
    unit_weight_kg, unit_volume_liter, barcode, temperature_zone,
    expiry_managed, fragile
) VALUES
  (1,  'ITEM-001', '생수 500mL 20입',          '음료',           'BOX',  10.500, 12.000, '8800000000001', 'AMBIENT', true,  false),
  (2,  'ITEM-002', '탄산수 라임 350mL 24입',   '음료',           'BOX',   8.800, 10.000, '8800000000002', 'AMBIENT', true,  false),
  (3,  'ITEM-003', '오렌지주스 1L 6입',        '음료',           'BOX',   6.500,  7.500, '8800000000003', 'AMBIENT', true,  false),
  (4,  'ITEM-004', '캔커피 240mL 30입',        '음료',           'BOX',   7.800,  9.000, '8800000000004', 'AMBIENT', true,  false),
  (5,  'ITEM-005', '녹차 500mL 20입',          '음료',           'BOX',  10.500, 12.000, '8800000000005', 'AMBIENT', true,  false),
  (6,  'ITEM-006', '스포츠음료 600mL 20입',    '음료',           'BOX',  12.500, 14.000, '8800000000006', 'AMBIENT', true,  false),
  (7,  'ITEM-007', '콜라 355mL 24입',          '음료',           'BOX',   9.000, 11.000, '8800000000007', 'AMBIENT', true,  false),
  (8,  'ITEM-008', '두유 190mL 24입',          '음료',           'BOX',   5.200,  7.000, '8800000000008', 'AMBIENT', true,  false),
  (9,  'ITEM-009', '즉석밥 210g 12입',         '식품',           'BOX',   2.800,  5.000, '8800000000009', 'AMBIENT', true,  false),
  (10, 'ITEM-010', '컵라면 매운맛 6입',        '식품',           'BOX',   0.800,  6.000, '8800000000010', 'AMBIENT', true,  false),
  (11, 'ITEM-011', '봉지라면 5입',             '식품',           'PACK',  0.650,  3.000, '8800000000011', 'AMBIENT', true,  false),
  (12, 'ITEM-012', '참치캔 150g 6입',          '식품',           'PACK',  1.100,  2.000, '8800000000012', 'AMBIENT', true,  false),
  (13, 'ITEM-013', '구운 아몬드 500g',         '식품',           'EA',    0.520,  1.200, '8800000000013', 'AMBIENT', true,  false),
  (14, 'ITEM-014', '그래놀라 시리얼 500g',     '식품',           'EA',    0.600,  4.000, '8800000000014', 'AMBIENT', true,  false),
  (15, 'ITEM-015', '스파게티면 500g 20입',     '식품',           'BOX',  10.500, 13.000, '8800000000015', 'AMBIENT', true,  false),
  (16, 'ITEM-016', '토마토소스 600g 12입',     '식품',           'BOX',   7.800,  9.000, '8800000000016', 'AMBIENT', true,  true),
  (17, 'ITEM-017', '물티슈 100매 10팩',        '생활용품',       'BOX',   4.500, 14.000, '8800000000017', 'AMBIENT', false, false),
  (18, 'ITEM-018', '화장지 30롤',              '생활용품',       'PACK',  6.000, 70.000, '8800000000018', 'AMBIENT', false, false),
  (19, 'ITEM-019', '키친타월 12롤',            '생활용품',       'PACK',  4.000, 45.000, '8800000000019', 'AMBIENT', false, false),
  (20, 'ITEM-020', '세탁세제 2.5L 4입',        '생활용품',       'BOX',  10.800, 13.000, '8800000000020', 'AMBIENT', false, false),
  (21, 'ITEM-021', '섬유유연제 2L 6입',        '생활용품',       'BOX',  12.600, 15.000, '8800000000021', 'AMBIENT', false, false),
  (22, 'ITEM-022', '주방세제 1L 8입',          '생활용품',       'BOX',   8.500, 11.000, '8800000000022', 'AMBIENT', false, false),
  (23, 'ITEM-023', '종량제봉투 20L 100매',     '생활용품',       'PACK',  2.000,  3.000, '8800000000023', 'AMBIENT', false, false),
  (24, 'ITEM-024', '지퍼백 중형 100매',        '생활용품',       'PACK',  1.200,  4.000, '8800000000024', 'AMBIENT', false, false),
  (25, 'ITEM-025', '샴푸 500mL 6입',           '뷰티·퍼스널케어','BOX',   3.400,  5.000, '8800000000025', 'AMBIENT', true,  false),
  (26, 'ITEM-026', '린스 500mL 6입',           '뷰티·퍼스널케어','BOX',   3.400,  5.000, '8800000000026', 'AMBIENT', true,  false),
  (27, 'ITEM-027', '바디워시 500mL 6입',       '뷰티·퍼스널케어','BOX',   3.400,  5.000, '8800000000027', 'AMBIENT', true,  false),
  (28, 'ITEM-028', '핸드워시 300mL 8입',       '뷰티·퍼스널케어','BOX',   2.800,  4.000, '8800000000028', 'AMBIENT', true,  false),
  (29, 'ITEM-029', '치약 120g 12입',           '뷰티·퍼스널케어','BOX',   1.800,  3.000, '8800000000029', 'AMBIENT', true,  false),
  (30, 'ITEM-030', '칫솔 12개입',              '뷰티·퍼스널케어','PACK',  0.500,  2.000, '8800000000030', 'AMBIENT', false, false),
  (31, 'ITEM-031', '폼클렌저 150mL 6입',       '뷰티·퍼스널케어','BOX',   1.000,  2.000, '8800000000031', 'AMBIENT', true,  false),
  (32, 'ITEM-032', '선크림 50mL 12입',         '뷰티·퍼스널케어','BOX',   0.900,  1.500, '8800000000032', 'AMBIENT', true,  false),
  (33, 'ITEM-033', 'USB-C 충전 케이블 1m',     '전자액세서리',   'EA',    0.080,  0.200, '8800000000033', 'AMBIENT', false, false),
  (34, 'ITEM-034', '라이트닝 충전 케이블 1m',  '전자액세서리',   'EA',    0.080,  0.200, '8800000000034', 'AMBIENT', false, false),
  (35, 'ITEM-035', '무선 마우스',              '전자액세서리',   'EA',    0.150,  0.600, '8800000000035', 'AMBIENT', false, true),
  (36, 'ITEM-036', '텐키리스 키보드',          '전자액세서리',   'EA',    0.700,  3.000, '8800000000036', 'AMBIENT', false, true),
  (37, 'ITEM-037', '보조배터리 10000mAh',      '전자액세서리',   'EA',    0.250,  0.400, '8800000000037', 'AMBIENT', false, true),
  (38, 'ITEM-038', 'AA 건전지 20개입',         '전자액세서리',   'PACK',  0.550,  0.800, '8800000000038', 'AMBIENT', false, false),
  (39, 'ITEM-039', 'LED 전구 10W 6개입',       '전자액세서리',   'PACK',  0.900,  5.000, '8800000000039', 'AMBIENT', false, true),
  (40, 'ITEM-040', '4구 멀티탭 3m',            '전자액세서리',   'EA',    0.650,  2.000, '8800000000040', 'AMBIENT', false, true),
  (41, 'ITEM-041', '면 티셔츠 블랙 M',         '의류',           'EA',    0.250,  1.000, '8800000000041', 'AMBIENT', false, false),
  (42, 'ITEM-042', '면 티셔츠 블랙 L',         '의류',           'EA',    0.280,  1.100, '8800000000042', 'AMBIENT', false, false),
  (43, 'ITEM-043', '면 티셔츠 화이트 M',       '의류',           'EA',    0.250,  1.000, '8800000000043', 'AMBIENT', false, false),
  (44, 'ITEM-044', '면 티셔츠 화이트 L',       '의류',           'EA',    0.280,  1.100, '8800000000044', 'AMBIENT', false, false),
  (45, 'ITEM-045', '후드 집업 그레이 M',       '의류',           'EA',    0.650,  3.000, '8800000000045', 'AMBIENT', false, false),
  (46, 'ITEM-046', '후드 집업 그레이 L',       '의류',           'EA',    0.700,  3.200, '8800000000046', 'AMBIENT', false, false),
  (47, 'ITEM-047', '조거 팬츠 블랙 M',         '의류',           'EA',    0.550,  2.500, '8800000000047', 'AMBIENT', false, false),
  (48, 'ITEM-048', '조거 팬츠 블랙 L',         '의류',           'EA',    0.600,  2.700, '8800000000048', 'AMBIENT', false, false),
  (49, 'ITEM-049', '강아지 사료 3kg',          '반려동물',       'EA',    3.100,  5.000, '8800000000049', 'AMBIENT', true,  false),
  (50, 'ITEM-050', '고양이 사료 2kg',          '반려동물',       'EA',    2.100,  4.000, '8800000000050', 'AMBIENT', true,  false),
  (51, 'ITEM-051', '고양이 모래 6L',           '반려동물',       'EA',    5.500,  7.000, '8800000000051', 'AMBIENT', false, false),
  (52, 'ITEM-052', '반려동물 배변패드 100매',  '반려동물',       'PACK',  4.000, 25.000, '8800000000052', 'AMBIENT', false, false),
  (53, 'ITEM-053', '강아지 간식 500g',         '반려동물',       'EA',    0.550,  2.000, '8800000000053', 'AMBIENT', true,  false),
  (54, 'ITEM-054', '고양이 캔 80g 24입',       '반려동물',       'BOX',   2.200,  3.500, '8800000000054', 'AMBIENT', true,  false),
  (55, 'ITEM-055', 'A4 복사용지 2500매',       '문구·오피스',    'BOX',  12.500, 14.000, '8800000000055', 'AMBIENT', false, false),
  (56, 'ITEM-056', '볼펜 검정 12개입',         '문구·오피스',    'PACK',  0.250,  0.500, '8800000000056', 'AMBIENT', false, false),
  (57, 'ITEM-057', 'A5 유선노트 10권',         '문구·오피스',    'PACK',  2.200,  4.000, '8800000000057', 'AMBIENT', false, false),
  (58, 'ITEM-058', '포장 테이프 48mm 12개입',  '문구·오피스',    'BOX',   0.900,  3.000, '8800000000058', 'AMBIENT', false, false),
  (59, 'ITEM-059', '라벨지 A4 100매',          '문구·오피스',    'PACK',  1.300,  2.000, '8800000000059', 'AMBIENT', false, false),
  (60, 'ITEM-060', '문서 보관파일 A4 20개입',  '문구·오피스',    'BOX',   2.000,  8.000, '8800000000060', 'AMBIENT', false, false)
ON CONFLICT (product_id) DO UPDATE SET
    product_code = EXCLUDED.product_code,
    product_name = EXCLUDED.product_name,
    category = EXCLUDED.category,
    unit = EXCLUDED.unit,
    unit_weight_kg = EXCLUDED.unit_weight_kg,
    unit_volume_liter = EXCLUDED.unit_volume_liter,
    barcode = EXCLUDED.barcode,
    temperature_zone = EXCLUDED.temperature_zone,
    expiry_managed = EXCLUDED.expiry_managed,
    fragile = EXCLUDED.fragile;

UPDATE public.product SET category = '기타' WHERE category IS NULL;
UPDATE public.product SET unit = 'EA' WHERE unit IS NULL;
UPDATE public.product SET unit_weight_kg = 0.100 WHERE unit_weight_kg IS NULL;
UPDATE public.product SET unit_volume_liter = 0.100 WHERE unit_volume_liter IS NULL;
UPDATE public.product SET temperature_zone = 'AMBIENT' WHERE temperature_zone IS NULL;
UPDATE public.product SET expiry_managed = false WHERE expiry_managed IS NULL;
UPDATE public.product SET fragile = false WHERE fragile IS NULL;

ALTER TABLE public.product ALTER COLUMN category SET NOT NULL;
ALTER TABLE public.product ALTER COLUMN unit SET NOT NULL;
ALTER TABLE public.product ALTER COLUMN unit_weight_kg SET NOT NULL;
ALTER TABLE public.product ALTER COLUMN unit_volume_liter SET NOT NULL;
ALTER TABLE public.product ALTER COLUMN temperature_zone SET NOT NULL;
ALTER TABLE public.product ALTER COLUMN expiry_managed SET NOT NULL;
ALTER TABLE public.product ALTER COLUMN fragile SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_product_barcode
    ON public.product(barcode)
    WHERE barcode IS NOT NULL;

SELECT setval(
    pg_get_serial_sequence('public.product', 'product_id'),
    GREATEST((SELECT COALESCE(MAX(product_id), 1) FROM public.product), 1),
    true
);
