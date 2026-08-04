BEGIN;

DO $$
BEGIN
    IF to_regclass('public.warehouse_items') IS NULL THEN
        RAISE EXCEPTION 'public.warehouse_items does not exist';
    END IF;

    IF to_regclass('public.product') IS NULL THEN
        RAISE EXCEPTION 'public.product does not exist';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'warehouse_items'
          AND column_name = 'item_id'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'warehouse_items'
          AND column_name = 'product_id'
    ) THEN
        ALTER TABLE public.warehouse_items RENAME COLUMN item_id TO product_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'warehouse_items'
          AND column_name = 'product_id'
    ) THEN
        RAISE EXCEPTION 'public.warehouse_items.product_id does not exist';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.warehouse_items wi
        LEFT JOIN public.product p ON p.product_id = wi.product_id
        WHERE p.product_id IS NULL
    ) THEN
        RAISE EXCEPTION 'warehouse_items contains product_id values missing from product';
    END IF;

    ALTER TABLE public.warehouse_items
        ALTER COLUMN product_id SET NOT NULL;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        WHERE c.conrelid = 'public.warehouse_items'::regclass
          AND c.confrelid = 'public.product'::regclass
          AND c.contype = 'f'
    ) THEN
        ALTER TABLE public.warehouse_items
            ADD CONSTRAINT fk_warehouse_items_product
            FOREIGN KEY (product_id)
            REFERENCES public.product(product_id)
            ON DELETE RESTRICT;
    END IF;
END
$$;

COMMIT;
