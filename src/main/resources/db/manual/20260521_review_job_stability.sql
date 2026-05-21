-- P7 review job stability migration.
-- This project does not currently run Flyway/Liquibase. Apply this manually before deploying the P7 backend.

alter table pull_request
    add column if not exists review_run_id varchar(36);
