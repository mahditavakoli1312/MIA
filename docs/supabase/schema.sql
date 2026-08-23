-- MIA — Supabase schema (projects + tasks)
--
-- Paste this whole file into the Supabase dashboard → SQL Editor → Run, on a fresh
-- project. It is idempotent: re-running it is safe.
--
-- Two things here are NOT optional, even though docs/README.md's prose sketch omits them:
--
--   1. projects.user_id defaults to auth.uid(). The app only ever sends {"name": ...}
--      (see CreateProjectBody), so a NOT NULL user_id with no default would reject every
--      insert — and without the column, per-user RLS has nothing to filter on.
--   2. tasks.project_id cascades on delete. The app deletes a project with a single
--      DELETE on projects (IntentExecutionRepository.deleteProject) and never clears its
--      tasks first, so a plain FK would raise a foreign-key violation on any project that
--      still has tasks.

create extension if not exists pgcrypto;

-- ── Tables ────────────────────────────────────────────────────────────────────
create table if not exists public.projects (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null default auth.uid()
                   references auth.users (id) on delete cascade,
    name       text not null,
    created_at timestamptz not null default now()
);

create table if not exists public.tasks (
    id         uuid primary key default gen_random_uuid(),
    project_id uuid not null references public.projects (id) on delete cascade,
    title      text not null,
    due_date   date,
    is_done    boolean not null default false,
    created_at timestamptz not null default now()
);

-- getProjects() embeds tasks (select=*,tasks(*)) and orders by created_at; findTasksByTitle
-- filters on project_id. These indexes are what keep those two paths from sequential scans.
create index if not exists tasks_project_id_idx  on public.tasks (project_id);
create index if not exists projects_user_id_idx  on public.projects (user_id);
create index if not exists projects_created_at_idx on public.projects (created_at);

-- ── Row Level Security ────────────────────────────────────────────────────────
-- Every row is owned by the user who created it. The anon publishable key alone can read
-- nothing: the app sends the signed-in user's access token as the Authorization bearer
-- (supabaseRestInterceptor), which is what makes auth.uid() resolve.
alter table public.projects enable row level security;
alter table public.tasks    enable row level security;

drop policy if exists "own projects" on public.projects;
create policy "own projects" on public.projects
    for all
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

-- Tasks inherit their owner from the parent project, so there is no user_id to keep in sync.
drop policy if exists "own tasks" on public.tasks;
create policy "own tasks" on public.tasks
    for all
    using (
        exists (
            select 1 from public.projects p
            where p.id = tasks.project_id and p.user_id = auth.uid()
        )
    )
    with check (
        exists (
            select 1 from public.projects p
            where p.id = tasks.project_id and p.user_id = auth.uid()
        )
    );
