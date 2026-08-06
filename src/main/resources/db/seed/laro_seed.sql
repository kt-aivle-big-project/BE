-- ============================================================================
-- LARO 통합 시드
--
-- 이 파일 하나만 실행하면 화면과 시뮬레이션이 모두 동작한다.
-- 조원마다 DB 내용이 달라지던 문제를 없애기 위한 단일 기준이다.
--
-- 들어가는 것
--   관리자 계정(admin@laro.com / Password123!), 품목 60종, 로봇 사양
--   창고 1 지도 (노드 268개 / 간선 356개)
--   충전소 10개, 랙 48개, 로봇 6대
--   3층 재고, 시나리오 프리셋 3개
--
-- 실행 방법 - 이 폴더에서 psql 로 연다
--   cd BE\src\main\resources\db\seed
--   psql -h localhost -p 5432 -U postgres -d warehouse -f laro_seed.sql
--
-- 테이블은 Hibernate 가 먼저 만들어야 한다. BE 를 한 번 띄운 뒤 실행한다.
-- 여러 번 실행해도 안전하다. 모든 문장이 ON CONFLICT 를 쓴다.
--
-- 주의
--   아래 \i 는 psql 전용 명령이다. DBeaver 같은 GUI 도구에 붙여넣으면
--   동작하지 않는다. 그럴 때는 파일 맨 아래 "완전 단일 파일" 설명을 본다.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. 기본 데이터 - 관리자 계정, 품목 5종, 로봇 사양
-- ----------------------------------------------------------------------------
\i V01_base.sql

-- ----------------------------------------------------------------------------
-- 2. 품목 카탈로그 60종
--    재고보다 먼저 들어가야 product_id 참조가 맞는다.
-- ----------------------------------------------------------------------------
\i V06_product_catalog.sql

-- ----------------------------------------------------------------------------
-- 3. 창고 1 지도, 충전소, 랙, 로봇
--    generate_warehouse_seed.py 가 지도 JSON 에서 만든 파일이다.
--    좌표와 코드가 AI(Neo4j) 계약과 맞물려 있으므로 손대지 않는다.
-- ----------------------------------------------------------------------------
\i V02_warehouse_1.sql

-- ----------------------------------------------------------------------------
-- 4. 초기 재고와 시나리오 프리셋
-- ----------------------------------------------------------------------------
\i V05_inventory.sql

-- ----------------------------------------------------------------------------
-- 5. 보관 모델 - 보관위치 한 칸 = 박스 한 개, 랙 3층
-- ----------------------------------------------------------------------------
\i V07_box_storage_model.sql
\i V08_three_level_rack_storage.sql
\i V09_remove_expiry.sql


-- ============================================================================
-- 7. 통합 시드에서만 적용하는 보정
--
-- 위 파일들을 그대로 넣으면 두 가지가 어긋난다. 여기서 바로잡는다.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 7-1. 로봇 상태값
--
-- V02 는 status 를 'AVAILABLE' 로 넣지만
-- RobotAvailabilityStatusConverter 는 'IDLE' / 'BUSY' 로 읽고 쓴다.
-- 그대로 두면 로봇 조회가 한 대도 못 찾아 시뮬레이션 시작이
-- 409 NO_AVAILABLE_ROBOTS 로 실패한다.
--
-- 낡은 CHECK 제약이 'AVAILABLE','UNAVAILABLE' 만 허용하므로
-- 제약을 먼저 지워야 값을 바꿀 수 있다.
-- ----------------------------------------------------------------------------
ALTER TABLE robot DROP CONSTRAINT IF EXISTS robot_status_check;

UPDATE robot
   SET status = CASE status
                  WHEN 'AVAILABLE'   THEN 'IDLE'
                  WHEN 'UNAVAILABLE' THEN 'BUSY'
                  ELSE status
                END
 WHERE status IN ('AVAILABLE', 'UNAVAILABLE');

ALTER TABLE robot
  ADD CONSTRAINT robot_status_check
  CHECK (status IN ('IDLE', 'BUSY', 'CHARGING'));

-- ----------------------------------------------------------------------------
-- 7-2. 3층 재고
--
-- V05 는 랙 10곳의 1층만 채운다. 그래서 기본 창고와
-- JSON 으로 만든 창고의 선반 모양이 달라 보였다.
-- 랙 절반의 1~3층을 모두 채워 둘을 같게 만든다.
-- ----------------------------------------------------------------------------
INSERT INTO warehouse_items (
    warehouse_id, storage_location_id, node_id, product_id,
    rack_level, received_at, quantity, inbound_quantity, outbound_quantity
)
SELECT
    ranked.warehouse_id,
    ranked.storage_location_id,
    ranked.node_id,
    ((ranked.rn - 1) * 3 + level.rack_level - 1)
        % (SELECT COUNT(*) FROM product) + 1,
    level.rack_level,
    NOW(),
    50,
    0,
    0
FROM (
    SELECT storage_location_id, warehouse_id, node_id,
           ROW_NUMBER() OVER (PARTITION BY warehouse_id
                              ORDER BY storage_location_id) AS rn,
           COUNT(*) OVER (PARTITION BY warehouse_id) AS total
    FROM storage_location
) ranked
CROSS JOIN (VALUES (1), (2), (3)) AS level(rack_level)
WHERE ranked.rn <= GREATEST(ranked.total / 2, 1)
ON CONFLICT DO NOTHING;

SELECT setval(pg_get_serial_sequence('warehouse_items', 'warehouse_item_id'),
              GREATEST((SELECT COALESCE(MAX(warehouse_item_id), 1)
                        FROM warehouse_items), 1), true);


-- ============================================================================
-- 8. 확인
-- ============================================================================
SELECT '창고'   AS 항목, count(*) AS 개수 FROM warehouse_layout
UNION ALL SELECT '노드',   count(*) FROM warehouse_node
UNION ALL SELECT '간선',   count(*) FROM warehouse_edge
UNION ALL SELECT '랙',     count(*) FROM storage_location
UNION ALL SELECT '충전소', count(*) FROM charging_station
UNION ALL SELECT '로봇',   count(*) FROM robot
UNION ALL SELECT '재고',   count(*) FROM warehouse_items
UNION ALL SELECT '품목',   count(*) FROM product
UNION ALL SELECT '시나리오', count(*) FROM scenario;

-- 기대값: 창고 1, 노드 268, 간선 356, 랙 48, 충전소 10, 로봇 6,
--         재고 72, 품목 60, 시나리오 3


-- ============================================================================
-- 완전 단일 파일이 필요하면
--
-- 위 \i 는 같은 폴더의 파일을 psql 이 읽어오는 방식이다.
-- 파일 하나에 내용까지 전부 담고 싶으면 이 폴더에서 한 줄만 실행한다.
--
--   type V01_base.sql V06_product_catalog.sql V02_warehouse_1.sql ^
--        V05_inventory.sql V07_box_storage_model.sql ^
--        V08_three_level_rack_storage.sql V09_remove_expiry.sql ^
--        > laro_full_seed.sql
--
-- 그 다음 위 6번 보정 SQL 을 laro_full_seed.sql 끝에 붙이면
-- 도커 컨테이너로도 바로 넣을 수 있다.
--
--   docker exec -i laro-be-centered-v13-27-postgres ^
--     psql -U postgres -d warehouse < laro_full_seed.sql
-- ============================================================================
