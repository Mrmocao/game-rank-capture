-- The independent metric worker is part of the continuous capture flow.
-- Only untouched configurations (revision 0) are enabled automatically; an
-- operator's explicit disable remains respected.
UPDATE capture_metric_config
SET enabled = TRUE,
    updated_at = UTC_TIMESTAMP(3)
WHERE revision = 0
  AND enabled = FALSE;
