CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT users_username_normalized CHECK (username = lower(username)),
    CONSTRAINT users_email_normalized CHECK (email = lower(email))
);

CREATE TABLE projects (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    conflict_policy VARCHAR(30) NOT NULL CHECK (conflict_policy IN ('LAST_WRITE_WINS', 'SMART_MERGE', 'ADMIN_APPROVAL'))
);

CREATE TABLE project_members (
    project_id UUID NOT NULL REFERENCES projects(id),
    user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(10) NOT NULL CHECK (role IN ('ADMIN', 'MEMBER')),
    PRIMARY KEY (project_id, user_id)
);

CREATE INDEX project_members_user_idx ON project_members(user_id);

CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    title VARCHAR(200) NOT NULL CHECK (length(trim(title)) > 0),
    description TEXT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),
    priority VARCHAR(10) NOT NULL CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    assignee_id UUID,
    due_date DATE,
    version BIGINT NOT NULL CHECK (version >= 0),
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    FOREIGN KEY (project_id, assignee_id) REFERENCES project_members(project_id, user_id)
);

CREATE INDEX tasks_project_idx ON tasks(project_id, created_at, id);

CREATE SEQUENCE accepted_operation_order;

CREATE TABLE task_change_history (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id),
    actor_id UUID NOT NULL REFERENCES users(id),
    operation_id UUID NOT NULL,
    operation_order BIGINT NOT NULL,
    operation_type VARCHAR(30) NOT NULL,
    field_name VARCHAR(30) NOT NULL,
    old_value TEXT,
    new_value TEXT,
    task_version BIGINT NOT NULL,
    affects_state BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX task_history_version_idx ON task_change_history(task_id, task_version, operation_order);

CREATE TABLE conflicts (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    task_id UUID NOT NULL REFERENCES tasks(id),
    field_name VARCHAR(30) NOT NULL CHECK (field_name IN ('title', 'description', 'status', 'priority', 'assigneeId', 'dueDate')),
    base_value TEXT,
    current_value TEXT,
    base_version BIGINT NOT NULL CHECK (base_version >= 0),
    status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'RESOLVED')),
    created_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    resolved_by UUID REFERENCES users(id),
    CONSTRAINT conflict_resolution_consistent CHECK (
        (status = 'PENDING' AND resolved_at IS NULL AND resolved_by IS NULL) OR
        (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL)
    )
);

CREATE UNIQUE INDEX one_pending_conflict_per_field ON conflicts(task_id, field_name) WHERE status = 'PENDING';
CREATE INDEX conflicts_project_idx ON conflicts(project_id, status, created_at);

CREATE TABLE conflict_variants (
    id UUID PRIMARY KEY,
    conflict_id UUID NOT NULL REFERENCES conflicts(id),
    operation_id UUID NOT NULL,
    author_id UUID NOT NULL REFERENCES users(id),
    proposed_value TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (conflict_id, operation_id)
);

CREATE TABLE sync_operations (
    operation_id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES users(id),
    task_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('APPLIED', 'MERGED', 'CONFLICT_PENDING', 'REJECTED')),
    current_version BIGINT,
    conflict_ids TEXT NOT NULL,
    error_code VARCHAR(50),
    error_message VARCHAR(300),
    http_status INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX sync_operations_actor_idx ON sync_operations(actor_id, created_at);
