CREATE TABLE IF NOT EXISTS board_post_attachments (
    attachment_id BIGSERIAL PRIMARY KEY,
    board_post_id BIGINT NOT NULL UNIQUE,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_board_post_attachments_post
        FOREIGN KEY (board_post_id)
        REFERENCES board_posts(board_post_id)
        ON DELETE CASCADE
);

ALTER TABLE board_post_attachments
    ADD COLUMN IF NOT EXISTS object_key VARCHAR(500);

DELETE FROM board_post_attachments
WHERE object_key IS NULL;

ALTER TABLE board_post_attachments
    ALTER COLUMN object_key SET NOT NULL,
    DROP COLUMN IF EXISTS file_data;
