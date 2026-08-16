-- Persist password hashes for the identity registry.
-- UserStore previously kept these in memory only; users were lost on restart.
ALTER TABLE identities ADD COLUMN password_hash TEXT;
