-- Hibernate generates a CHECK constraint listing MessageStatus values when it creates the
-- messages table, but ddl-auto=update never revisits it - so a database created before
-- READ existed would reject READ. Rewrite it here; on a fresh database the table doesn't
-- exist yet (this runs before Hibernate), so this is skipped and Hibernate creates it with
-- every current value. A real migration tool would make this unnecessary.
ALTER TABLE IF EXISTS messages
    DROP CONSTRAINT IF EXISTS messages_status_check,
    ADD CONSTRAINT messages_status_check CHECK (status IN ('SENT', 'DELIVERED', 'READ'));

-- Same ddl-auto=update gap: recipient_id was created NOT NULL, and update never relaxes
-- that. Group messages leave it null (they carry group_id instead).
ALTER TABLE IF EXISTS messages ALTER COLUMN recipient_id DROP NOT NULL;
