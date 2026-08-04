-- ============================================================================
-- 공통 기본 데이터
--
-- 앱이 시작될 때마다 실행된다.
-- 이미 있으면 건너뛰므로 여러 번 실행해도 안전하다.
--
-- 창고별 지도는 V02_warehouse_1.sql 부터 이어서 들어간다.
-- ============================================================================

-- 참고: enum 값이 바뀐 뒤 낡은 CHECK 제약 때문에 INSERT 가 막히면
--       tools/repair_check_constraints.sql 을 수동으로 한 번 실행한다.
--       (여러 줄 블록은 자동 실행에서 쪼개지므로 여기 둘 수 없다)

-- ---------------------------------------------------------------------------
-- 관리자 계정
-- 비밀번호: Password123!
-- ---------------------------------------------------------------------------
INSERT INTO users (user_id, email, name, password_hash, failed_login_attempts)
VALUES (1, 'admin@laro.com', '관리자',
        '$2b$10$ZMlP7GlI18WvcBB.efwc2.Df2XabB3MjEWg24fIXesSvu1DBb55Da', 0)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 품목
-- ---------------------------------------------------------------------------
-- Existing upgraded databases can have additional NOT NULL catalog columns.
-- Skip this tiny bootstrap catalog when products already exist; V06 performs
-- the authoritative full-column upsert later in the same startup sequence.
DO '
BEGIN
IF NOT EXISTS (SELECT 1 FROM product) THEN
INSERT INTO product (
    product_id, product_code, product_name, category, unit,
    units_per_box, unit_weight_kg, unit_volume_liter,
    temperature_zone, fragile
) VALUES
  (1, ''ITEM-001'', ''Bootstrap Item 001'', ''BOOTSTRAP'', ''BOX'', 1, 1.000, 1.000, ''AMBIENT'', false),
  (2, ''ITEM-002'', ''Bootstrap Item 002'', ''BOOTSTRAP'', ''BOX'', 1, 1.000, 1.000, ''AMBIENT'', false),
  (3, ''ITEM-003'', ''Bootstrap Item 003'', ''BOOTSTRAP'', ''BOX'', 1, 1.000, 1.000, ''AMBIENT'', false),
  (4, ''ITEM-004'', ''Bootstrap Item 004'', ''BOOTSTRAP'', ''BOX'', 1, 1.000, 1.000, ''AMBIENT'', false),
  (5, ''ITEM-005'', ''Bootstrap Item 005'', ''BOOTSTRAP'', ''BOX'', 1, 1.000, 1.000, ''AMBIENT'', false)
ON CONFLICT DO NOTHING;
END IF;
END
';

-- ---------------------------------------------------------------------------
-- 로봇 사양
-- ---------------------------------------------------------------------------
INSERT INTO robot_specs (id, robot_code, task_code,
                         base_battery_rate, work_battery_rate, failure_rate)
VALUES (1, 'AGV-100', 'GENERAL', 0.05, 0.15, 0.01)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 자동 증가 값 정리
-- ---------------------------------------------------------------------------
SELECT setval(pg_get_serial_sequence('users', 'user_id'),
              GREATEST((SELECT COALESCE(MAX(user_id), 1) FROM users), 1), true);
SELECT setval(pg_get_serial_sequence('product', 'product_id'),
              GREATEST((SELECT COALESCE(MAX(product_id), 1) FROM product), 1), true);
SELECT setval(pg_get_serial_sequence('robot_specs', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 1) FROM robot_specs), 1), true);
