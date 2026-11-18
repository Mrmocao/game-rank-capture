-- Support the report's candidate aggregation without scanning unrelated dates.
ALTER TABLE external_ranking_observation
  ADD KEY idx_external_observation_report
    (tenant_id,source_key,chart_key,business_date,captured_at,available_at,id);

ALTER TABLE external_ranking_entry
  ADD KEY idx_external_entry_report_identity
    (observation_id,external_id,rank_position);
