-- LARO does not use expiry-based product or inventory policies.
-- Drop the compatibility view first because older versions selected these
-- columns. The AI schema refresher recreates the no-expiry view on next use.
DROP VIEW IF EXISTS laro_ext.be_inventory_unit_v;

ALTER TABLE public.warehouse_items
    DROP COLUMN IF EXISTS expiry_date;

ALTER TABLE public.product
    DROP COLUMN IF EXISTS expiry_managed;
