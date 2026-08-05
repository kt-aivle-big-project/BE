BEGIN;

DO $$
BEGIN
    IF to_regclass('public.product') IS NULL THEN
        RAISE EXCEPTION 'public.product does not exist';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.product
        WHERE (product_code = 'ITEM-001' AND product_id <> 1)
           OR (product_code = 'ITEM-002' AND product_id <> 2)
           OR (product_code = 'ITEM-003' AND product_id <> 3)
           OR (product_code = 'ITEM-004' AND product_id <> 4)
           OR (product_code = 'ITEM-005' AND product_id <> 5)
    ) THEN
        RAISE EXCEPTION 'An ITEM-00N target code is already assigned to another product';
    END IF;

    UPDATE public.product
    SET product_code = CASE product_id
        WHEN 1 THEN 'ITEM-001'
        WHEN 2 THEN 'ITEM-002'
        WHEN 3 THEN 'ITEM-003'
        WHEN 4 THEN 'ITEM-004'
        WHEN 5 THEN 'ITEM-005'
    END
    WHERE product_id BETWEEN 1 AND 5
      AND product_code IN ('A', 'B', 'C', 'D', 'E');
END
$$;

COMMIT;
