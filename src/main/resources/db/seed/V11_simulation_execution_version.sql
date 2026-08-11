ALTER TABLE IF EXISTS public.simulation_runs
    ADD COLUMN IF NOT EXISTS execution_version BIGINT NOT NULL DEFAULT 1;
