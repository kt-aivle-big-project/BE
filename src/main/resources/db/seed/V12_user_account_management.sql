ALTER TABLE users
    DROP COLUMN IF EXISTS deleted_at;

ALTER TABLE board_posts
    ALTER COLUMN user_id DROP NOT NULL;

DO '
DECLARE
    target_table oid;
    foreign_key record;
    delete_action text;
    constraint_definition text;
    discovered_tables oid[] := ARRAY[''users''::regclass::oid];
    processed_constraints text[] := ARRAY[]::text[];
BEGIN
    LOOP
        SELECT constraint_row.*
        INTO foreign_key
        FROM pg_constraint constraint_row
        WHERE constraint_row.contype = ''f''
          AND constraint_row.confrelid = ANY(discovered_tables)
          AND NOT (
              constraint_row.conrelid::text || '':'' || constraint_row.conname
          ) = ANY(processed_constraints)
        ORDER BY constraint_row.oid
        LIMIT 1;

        EXIT WHEN NOT FOUND;
        processed_constraints := array_append(
            processed_constraints,
            foreign_key.conrelid::text || '':'' || foreign_key.conname
        );

        IF foreign_key.conrelid = ''board_posts''::regclass::oid THEN
            delete_action := ''SET NULL'';
        ELSIF foreign_key.conrelid = ''warehouse_layout''::regclass::oid
              AND foreign_key.conkey = ARRAY[
                  (SELECT attnum FROM pg_attribute
                   WHERE attrelid = ''warehouse_layout''::regclass
                     AND attname = ''source_template_id'')
              ]::smallint[] THEN
            delete_action := ''SET NULL'';
        ELSE
            delete_action := ''CASCADE'';
            target_table := foreign_key.conrelid;
            IF NOT target_table = ANY(discovered_tables) THEN
                discovered_tables := array_append(discovered_tables, target_table);
            END IF;
        END IF;

        constraint_definition := regexp_replace(
            pg_get_constraintdef(foreign_key.oid),
            '' ON DELETE (NO ACTION|RESTRICT|CASCADE|SET NULL|SET DEFAULT)'',
            '''',
            ''i''
        );

        EXECUTE format(
            ''ALTER TABLE %s DROP CONSTRAINT %I'',
            foreign_key.conrelid::regclass,
            foreign_key.conname
        );
        EXECUTE format(
            ''ALTER TABLE %s ADD CONSTRAINT %I %s ON DELETE %s'',
            foreign_key.conrelid::regclass,
            foreign_key.conname,
            constraint_definition,
            delete_action
        );
    END LOOP;
END
';
