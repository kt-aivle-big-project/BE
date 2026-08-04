-- Expand the existing Hibernate-generated warehouse_node node_type check.
-- Hibernate ddl-auto=update adds enum-backed columns but does not revise an
-- already-created CHECK constraint when Java enum constants are added.

ALTER TABLE public.warehouse_node
    DROP CONSTRAINT IF EXISTS warehouse_node_node_type_check;

ALTER TABLE public.warehouse_node
    ADD CONSTRAINT warehouse_node_node_type_check
    CHECK (node_type IN (
        'ROUTE',
        'ROUTE_CHARGE_JUNCTION',
        'RACK_STORAGE',
        'RACK_ACCESS',
        'INBOUND_HANDOFF_ACCESS',
        'OUTBOUND_STATION_ACCESS',
        'EMPTY_TOTE_BUFFER_ACCESS',
        'INBOUND',
        'OUTBOUND',
        'CHARGING_SLOT',
        'PARKING_SLOT'
    ));
