-- P6 repository-scoped AI settings migration.
-- This project does not currently run Flyway/Liquibase. Apply this manually before deploying the P6 backend.

create table if not exists repository_ai_settings (
    id bigserial primary key,
    repository_id bigint not null,
    owner varchar(255) not null,
    repository_name varchar(255) not null,
    webhook_secret varchar(255) not null,
    webhook_registered_by_id bigint,
    posting_account_id bigint,
    posting_account_login varchar(255),
    review_tone varchar(255) not null default 'NEUTRAL',
    review_focus varchar(255) not null default 'BOTH',
    detail_level varchar(255) not null default 'STANDARD',
    ignore_patterns varchar(1000) default 'package-lock.json, yarn.lock, *.lock, .env*, *.pem, *.key, .yml, .yaml',
    open_ai_key varchar(255),
    auto_review_enabled boolean not null default false,
    auto_post_to_github boolean not null default false,
    openai_model varchar(255) not null default 'gpt-4o-mini',
    constraint uk_repository_ai_settings_repo unique (repository_id),
    constraint fk_repository_ai_settings_webhook_account foreign key (webhook_registered_by_id) references github_account(id),
    constraint fk_repository_ai_settings_posting_account foreign key (posting_account_id) references github_account(id)
);

do $$
begin
    if to_regclass('rule') is not null then
        alter table rule
            add column if not exists repository_settings_id bigint;

        if not exists (
            select 1
            from pg_constraint
            where conname = 'fk_rule_repository_settings'
        ) then
            alter table rule
                add constraint fk_rule_repository_settings
                foreign key (repository_settings_id) references repository_ai_settings(id);
        end if;
    end if;
end $$;

do $$
begin
    if to_regclass('pull_request') is null or to_regclass('github_account') is null then
        return;
    end if;

    if to_regclass('ai_review_settings') is not null then
        execute $migration$
            insert into repository_ai_settings (
                repository_id,
                owner,
                repository_name,
                webhook_secret,
                webhook_registered_by_id,
                posting_account_id,
                posting_account_login,
                review_tone,
                review_focus,
                detail_level,
                ignore_patterns,
                open_ai_key,
                auto_review_enabled,
                auto_post_to_github,
                openai_model
            )
            select distinct on (pr.repository_id)
                pr.repository_id,
                ga.login_id,
                pr.repository_name,
                ga.webhook_secret,
                ga.id,
                ga.id,
                ga.login_id,
                coalesce(ars.review_tone, 'NEUTRAL'),
                coalesce(ars.review_focus, 'BOTH'),
                coalesce(ars.detail_level, 'STANDARD'),
                coalesce(ars.ignore_patterns, 'package-lock.json, yarn.lock, *.lock, .env*, *.pem, *.key, .yml, .yaml'),
                ars.open_ai_key,
                coalesce(ars.auto_review_enabled, false),
                coalesce(ars.auto_post_to_github, false),
                coalesce(ars.openai_model, 'gpt-4o-mini')
            from pull_request pr
            join github_account ga on ga.id = pr.github_account_id
            left join ai_review_settings ars on ars.id = ga.id
            on conflict (repository_id) do nothing
        $migration$;

        if to_regclass('rule') is not null then
            execute $migration$
                insert into rule (repository_settings_id, content, is_enabled, target_file_pattern)
                select ras.id, r.content, r.is_enabled, r.target_file_pattern
                from rule r
                join ai_review_settings ars on ars.id = r.settings_id
                join github_account ga on ga.id = ars.id
                join repository_ai_settings ras on ras.posting_account_id = ga.id
                where r.repository_settings_id is null
                  and not exists (
                      select 1
                      from rule existing
                      where existing.repository_settings_id = ras.id
                        and existing.content = r.content
                        and coalesce(existing.target_file_pattern, '') = coalesce(r.target_file_pattern, '')
                  )
            $migration$;
        end if;
    else
        execute $migration$
            insert into repository_ai_settings (
                repository_id,
                owner,
                repository_name,
                webhook_secret,
                webhook_registered_by_id,
                posting_account_id,
                posting_account_login
            )
            select distinct on (pr.repository_id)
                pr.repository_id,
                ga.login_id,
                pr.repository_name,
                ga.webhook_secret,
                ga.id,
                ga.id,
                ga.login_id
            from pull_request pr
            join github_account ga on ga.id = pr.github_account_id
            on conflict (repository_id) do nothing
        $migration$;
    end if;
end $$;
