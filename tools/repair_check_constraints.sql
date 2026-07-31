-- ============================================================================
-- 낡은 CHECK 제약 정리 (수동 실행)
--
-- 언제 필요한가
--   enum 값이 바뀐 뒤 기존 DB 에 INSERT 가 막힐 때.
--   예: robot.status 가 IDLE/BUSY -> AVAILABLE/UNAVAILABLE 로 바뀐 경우
--
-- 왜 자동 실행에 못 넣는가
--   Spring 은 SQL 을 세미콜론으로 잘라서 실행한다.
--   아래 DO 블록은 안에 세미콜론이 있어 중간에 잘린다.
--
-- 실행
--   docker exec -i warehouse-postgres psql -U warehouse -d warehouse \
--     -f /tmp/repair.sql
-- ============================================================================

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT conname, conrelid::regclass AS tbl
        FROM pg_constraint
        WHERE contype = 'c'
          AND conrelid::regclass::text IN (
              'robot', 'simulation_runs', 'task', 'event',
              'charging_station', 'warehouse_zone', 'warehouse_node',
              'simulation', 'scenario', 'storage_location', 'warehouse_items'
          )
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.tbl, r.conname);
        RAISE NOTICE '제거: %.%', r.tbl, r.conname;
    END LOOP;
END $$;
