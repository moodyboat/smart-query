-- Add source tracking to mining models
ALTER TABLE sq_mining_model ADD COLUMN source VARCHAR(20) DEFAULT 'manual' AFTER deleted;

-- Backfill existing chat-created models (they have conversation_id set)
UPDATE sq_mining_model SET source = 'chat' WHERE conversation_id IS NOT NULL AND conversation_id > 0;
