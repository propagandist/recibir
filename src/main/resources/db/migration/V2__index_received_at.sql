-- EventProjector は、直近に受信したイベントのアドレスを窓で拾う（docs/SPEC.md §4.4）。
-- V1 が張った索引は先頭列が email / sg_message_id / job_id のいずれかで、
-- received_at だけを条件にした検索には効かない。
--
-- 窓に入るのは 1 分あたり数件である（同 §4.4 の月数万通規模）。
-- ただし索引が無ければ、その数件を見つけるために毎分すべての行を走ることになり、
-- sendgrid_event は append-only なので走る量だけが増え続ける。
--
-- DESC で作るのは、窓が「新しい側から一定期間」を切り出す形だからである。
CREATE INDEX idx_sendgrid_event_received_at ON sendgrid_event (received_at DESC);
