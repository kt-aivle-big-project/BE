-- One storage location = one physical BOX.
-- Inventory quantity is the number of sellable units currently inside that BOX.
-- A mobile robot transports one BOX per trip.

ALTER TABLE public.product
    ADD COLUMN IF NOT EXISTS units_per_box integer NOT NULL DEFAULT 1;

UPDATE public.product
SET unit = 'EA',
    units_per_box = CASE product_id
        WHEN 1 THEN 20 WHEN 2 THEN 24 WHEN 3 THEN 6 WHEN 4 THEN 30
        WHEN 5 THEN 20 WHEN 6 THEN 20 WHEN 7 THEN 24 WHEN 8 THEN 24
        WHEN 9 THEN 12 WHEN 10 THEN 6 WHEN 11 THEN 5 WHEN 12 THEN 6
        WHEN 13 THEN 12 WHEN 14 THEN 8 WHEN 15 THEN 20 WHEN 16 THEN 12
        WHEN 17 THEN 10 WHEN 18 THEN 4 WHEN 19 THEN 4 WHEN 20 THEN 4
        WHEN 21 THEN 6 WHEN 22 THEN 8 WHEN 23 THEN 10 WHEN 24 THEN 10
        WHEN 25 THEN 6 WHEN 26 THEN 6 WHEN 27 THEN 6 WHEN 28 THEN 8
        WHEN 29 THEN 12 WHEN 30 THEN 12 WHEN 31 THEN 6 WHEN 32 THEN 12
        WHEN 33 THEN 50 WHEN 34 THEN 50 WHEN 35 THEN 20 WHEN 36 THEN 10
        WHEN 37 THEN 20 WHEN 38 THEN 20 WHEN 39 THEN 6 WHEN 40 THEN 10
        WHEN 41 THEN 30 WHEN 42 THEN 30 WHEN 43 THEN 30 WHEN 44 THEN 30
        WHEN 45 THEN 12 WHEN 46 THEN 12 WHEN 47 THEN 12 WHEN 48 THEN 12
        WHEN 49 THEN 6 WHEN 50 THEN 6 WHEN 51 THEN 4 WHEN 52 THEN 4
        WHEN 53 THEN 12 WHEN 54 THEN 24 WHEN 55 THEN 5 WHEN 56 THEN 12
        WHEN 57 THEN 10 WHEN 58 THEN 12 WHEN 59 THEN 10 WHEN 60 THEN 20
        ELSE GREATEST(COALESCE(units_per_box, 1), 1)
    END;

-- Split legacy oversized rows across empty locations without losing stock.
-- The original row remains the first BOX; newly inserted rows are additional
-- BOXes of the same product in empty slots of the same warehouse.
DO '
DECLARE
    oversized record;
    free_location record;
    remaining integer;
    box_quantity integer;
BEGIN
    FOR oversized IN
        SELECT wi.*, p.units_per_box
        FROM public.warehouse_items wi
        JOIN public.product p ON p.product_id = wi.product_id
        WHERE wi.quantity > p.units_per_box
        ORDER BY wi.warehouse_id, wi.warehouse_item_id
    LOOP
        remaining := oversized.quantity - oversized.units_per_box;

        UPDATE public.warehouse_items
        SET quantity = oversized.units_per_box
        WHERE warehouse_item_id = oversized.warehouse_item_id;

        WHILE remaining > 0 LOOP
            SELECT sl.storage_location_id, sl.node_id
            INTO free_location
            FROM public.storage_location sl
            WHERE sl.warehouse_id = oversized.warehouse_id
              AND NOT EXISTS (
                  SELECT 1
                  FROM public.warehouse_items occupied
                  WHERE occupied.storage_location_id = sl.storage_location_id
              )
            ORDER BY sl.storage_location_id
            LIMIT 1;

            IF NOT FOUND THEN
                RAISE EXCEPTION
                    ''Not enough empty storage locations to split warehouse_item_id % into one-BOX slots'',
                    oversized.warehouse_item_id;
            END IF;

            box_quantity := LEAST(remaining, oversized.units_per_box);
            INSERT INTO public.warehouse_items (
                warehouse_id, storage_location_id, node_id, product_id,
                received_at, quantity,
                inbound_quantity, outbound_quantity
            ) VALUES (
                oversized.warehouse_id,
                free_location.storage_location_id,
                free_location.node_id,
                oversized.product_id,
                oversized.received_at,
                box_quantity,
                0,
                0
            );
            remaining := remaining - box_quantity;
        END LOOP;
    END LOOP;
END
';

DO '
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = ''ck_product_units_per_box_positive''
    ) THEN
        ALTER TABLE public.product
            ADD CONSTRAINT ck_product_units_per_box_positive CHECK (units_per_box > 0);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = ''ck_warehouse_items_quantity_nonnegative''
    ) THEN
        ALTER TABLE public.warehouse_items
            ADD CONSTRAINT ck_warehouse_items_quantity_nonnegative CHECK (quantity >= 0);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = ''uk_warehouse_items_storage_location''
    ) THEN
        ALTER TABLE public.warehouse_items
            ADD CONSTRAINT uk_warehouse_items_storage_location UNIQUE (storage_location_id);
    END IF;

    IF to_regclass(''laro_ext.robot_profile'') IS NOT NULL THEN
        UPDATE laro_ext.robot_profile SET capacity_units = 1, updated_at = now();
    END IF;
END
';

-- box_capacity was briefly introduced as a constant value of 1. It carries no
-- state: occupancy is represented by the warehouse_items row itself, while the
-- unique storage_location_id constraint enforces at most one BOX per slot.
DO '
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = ''public''
          AND table_name = ''storage_location''
          AND column_name = ''box_capacity''
    ) THEN
        -- The old AI inventory view depended on this column. It is recreated
        -- automatically by the AI schema refresher on its next read/startup.
        IF to_regnamespace(''laro_ext'') IS NOT NULL THEN
            DROP VIEW IF EXISTS laro_ext.be_inventory_unit_v;
        END IF;
        ALTER TABLE public.storage_location
            DROP CONSTRAINT IF EXISTS ck_storage_location_one_box;
        ALTER TABLE public.storage_location
            DROP COLUMN box_capacity;
    END IF;
END
';
