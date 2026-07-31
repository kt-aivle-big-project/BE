-- ============================================================================
-- 창고별 초기 재고와 시나리오 프리셋
--
-- 창고 지도(V02~V04)가 먼저 들어와야 한다. 보관위치를 참조하기 때문이다.
-- 이미 있으면 건너뛴다.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 초기 재고
--
-- 창고마다 앞쪽 랙 10곳에 품목을 50개씩 넣는다.
-- 출고 작업이 집어갈 물건이 없으면 시뮬레이션이 아무것도 못 한다.
--
-- 보관위치 ID 는 창고 ID 대역을 따른다. (창고 1 -> 10001~, 창고 2 -> 20001~)
-- ---------------------------------------------------------------------------
INSERT INTO warehouse_items (
    warehouse_item_id, warehouse_id, storage_location_id, node_id,
    item_id, received_at, quantity, inbound_quantity, outbound_quantity
)
SELECT
    sl.storage_location_id,
    sl.warehouse_id,
    sl.storage_location_id,
    sl.node_id,
    -- 품목 5종을 돌아가며 배정한다
    ((ROW_NUMBER() OVER (PARTITION BY sl.warehouse_id ORDER BY sl.storage_location_id) - 1) % 5) + 1,
    NOW(),
    50,
    0,
    0
FROM storage_location sl
WHERE sl.storage_location_id IN (
    SELECT storage_location_id
    FROM (
        SELECT storage_location_id, warehouse_id,
               ROW_NUMBER() OVER (PARTITION BY warehouse_id ORDER BY storage_location_id) AS rn
        FROM storage_location
    ) ranked
    WHERE ranked.rn <= 10
)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 시나리오 프리셋 (창고마다 3개)
--
-- 화면에서 고르는 실행 설정이다. 로봇 대수, 배속 같은 값.
-- ---------------------------------------------------------------------------
INSERT INTO scenario (
    scenario_id, warehouse_id, scenario_code, scenario_name,
    robot_count, simulation_speed, charging_threshold, auto_replan, obstacle_enabled
)
SELECT
    w.id * 100 + preset.preset_no,
    w.id,
    'S' || preset.preset_no,
    preset.label,
    preset.robots,
    preset.speed,
    20,
    true,
    preset.obstacle
FROM warehouse_layout w
CROSS JOIN (
    VALUES
        (1, '기본',      5, 1.0, false),
        (2, '고속',      5, 2.0, false),
        (3, '장애물 포함', 5, 1.0, true)
) AS preset(preset_no, label, robots, speed, obstacle)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 자동 증가 값 정리
-- ---------------------------------------------------------------------------
SELECT setval(pg_get_serial_sequence('warehouse_items', 'warehouse_item_id'),
              GREATEST((SELECT COALESCE(MAX(warehouse_item_id), 1) FROM warehouse_items), 1), true);
SELECT setval(pg_get_serial_sequence('scenario', 'scenario_id'),
              GREATEST((SELECT COALESCE(MAX(scenario_id), 1) FROM scenario), 1), true);
