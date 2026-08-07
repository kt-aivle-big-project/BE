ALTER TABLE warehouse_layout
    ADD COLUMN IF NOT EXISTS guest_session_id VARCHAR(36);

ALTER TABLE warehouse_layout
    ALTER COLUMN user_id DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_warehouse_layout_guest_source_template
    ON warehouse_layout (guest_session_id, source_template_id);
