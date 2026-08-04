-- One physical storage_location is a three-level rack.
-- Each (storage_location_id, rack_level) pair stores at most one physical BOX.

ALTER TABLE public.warehouse_items
    ADD COLUMN IF NOT EXISTS rack_level integer;

-- Preserve every existing BOX at level 1. The previous model allowed at most
-- one warehouse_items row per storage_location, so this migration is lossless.
UPDATE public.warehouse_items
SET rack_level = 1
WHERE rack_level IS NULL;

ALTER TABLE public.warehouse_items
    ALTER COLUMN rack_level SET DEFAULT 1,
    ALTER COLUMN rack_level SET NOT NULL;

ALTER TABLE public.warehouse_items
    DROP CONSTRAINT IF EXISTS uk_warehouse_items_storage_location;

DO '
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = ''ck_warehouse_items_rack_level''
    ) THEN
        ALTER TABLE public.warehouse_items
            ADD CONSTRAINT ck_warehouse_items_rack_level
            CHECK (rack_level BETWEEN 1 AND 3);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = ''uk_warehouse_items_storage_location_level''
    ) THEN
        ALTER TABLE public.warehouse_items
            ADD CONSTRAINT uk_warehouse_items_storage_location_level
            UNIQUE (storage_location_id, rack_level);
    END IF;
END
';

-- Keep the optional AI compatibility profile aligned with the promoted BE
-- column when the extension schema is already installed.
DO '
BEGIN
    IF to_regclass(''laro_ext.warehouse_item_profile'') IS NOT NULL THEN
        INSERT INTO laro_ext.warehouse_item_profile (
            warehouse_item_id,
            rack_level,
            capacity,
            planning_status,
            version,
            updated_at
        )
        SELECT wi.warehouse_item_id,
               wi.rack_level,
               p.units_per_box,
               ''STORED'',
               1,
               now()
        FROM public.warehouse_items wi
        JOIN public.product p ON p.product_id = wi.product_id
        ON CONFLICT (warehouse_item_id) DO UPDATE SET
            rack_level = EXCLUDED.rack_level,
            capacity = EXCLUDED.capacity,
            version = laro_ext.warehouse_item_profile.version + 1,
            updated_at = now();
    END IF;
END
';
