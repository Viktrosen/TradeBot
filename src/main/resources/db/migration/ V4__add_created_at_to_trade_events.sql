-- Добавляем колонку created_at, если её нет
ALTER TABLE trade_events
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();

-- Заполняем created_at для существующих записей из processed_at (если есть)
UPDATE trade_events
SET created_at = processed_at
WHERE created_at IS NULL AND processed_at IS NOT NULL;

-- Для записей без processed_at ставим текущее время
UPDATE trade_events
SET created_at = NOW()
WHERE created_at IS NULL;

-- Делаем колонку NOT NULL после заполнения
ALTER TABLE trade_events
    ALTER COLUMN created_at SET NOT NULL;