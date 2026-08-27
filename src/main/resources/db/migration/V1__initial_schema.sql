-- recibir の初期スキーマ。
-- カラムと型の正本は docs/SPEC.md §3、テーブル間の関係は docs/er.md にある。
--
-- 外部キー制約を 1 つも張っていない。これは張り忘れではなく判断である。
-- 受信エンドポイントは署名を検証して INSERT を 1 回打つだけで、業務判断をしない。
-- 親行が無い組み合わせは正常に起こりうる（job_id が入っていないイベント、
-- 状態行がまだ生えていないイベント、イベントが 1 件も無い状態行）。
-- 制約を張ると、その 3 つが受信の失敗になり、SendGrid が再送を積み上げる。
-- 理由の全文は docs/er.md「なぜ外部キー制約を張らないか」にある。

-- 受信したイベントを解釈せずそのまま保持する。このテーブルが正本であり、
-- 他のテーブルはここから再構築できなければならない（docs/SPEC.md §3.1）。
CREATE TABLE sendgrid_event (
    id            BIGSERIAL    PRIMARY KEY,
    -- 冪等キー。SendGrid は同じイベントを再送するので、ここで弾く。
    sg_event_id   TEXT         NOT NULL UNIQUE,
    -- 接尾辞が付いたまま保存する。切り落とすと元に戻せない。
    sg_message_id TEXT,
    smtp_id       TEXT,
    email         TEXT         NOT NULL,
    event         TEXT         NOT NULL,
    -- イベントの type。bounce / blocked。
    bounce_type   TEXT,
    reason        TEXT,
    -- SMTP ステータスコード。
    status        TEXT,
    -- custom_args 由来。送信時に入れていなければ NULL のままになる。
    job_id        TEXT,
    -- SendGrid 側の timestamp。received_at とはずれる。
    occurred_at   TIMESTAMPTZ  NOT NULL,
    received_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 受信した生 JSON。DTO へ射影した時点で捨てた情報は戻らないので、
    -- ここを正本にする（docs/er.md「正本は sendgrid_event」）。
    payload       JSONB        NOT NULL
);

CREATE INDEX idx_sendgrid_event_sg_message_id ON sendgrid_event (sg_message_id);

-- EventProjector は対象アドレスの全イベントを時系列で読む（docs/SPEC.md §4.4）。
CREATE INDEX idx_sendgrid_event_email_occurred_at ON sendgrid_event (email, occurred_at DESC);

CREATE INDEX idx_sendgrid_event_job_id ON sendgrid_event (job_id);

-- 失敗系イベントの部分インデックス。4 種を挙げているのは、
-- docs/SPEC.md §4.4 の状態決定の優先度に出てくるものがこれだからである。
-- delivered と processed は状態を落とさないので、ここには要らない。
CREATE INDEX idx_sendgrid_event_failures ON sendgrid_event (email, occurred_at DESC)
    WHERE event IN ('spamreport', 'bounce', 'dropped', 'deferred');

-- 「このアドレスに送ってよいか」の唯一の判断材料。
-- sendgrid_event からいつでも再構築できる二次テーブルであり、捨てて作り直せる。
CREATE TABLE email_address_state (
    email             TEXT        PRIMARY KEY,
    sendable          BOOLEAN     NOT NULL DEFAULT TRUE,
    -- hard_bounce / spam_report / unsubscribe / invalid
    reason_code       TEXT,
    last_failure_at   TIMESTAMPTZ,
    soft_bounce_count INT         NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ NOT NULL
);

-- 送信側の記録。job_id だけを置く。
-- docs/SPEC.md §3.3 は「最小限のスキーマのみ提供する」とだけ書き、カラムを定めていない。
-- メール送信そのものは同 §2 の「含まない」に入るので、
-- ここを埋めるのは送信側を書く人の仕事である（docs/er.md）。
CREATE TABLE mail_job (
    job_id TEXT PRIMARY KEY
);
