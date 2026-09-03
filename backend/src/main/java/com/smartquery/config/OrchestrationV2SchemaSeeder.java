package com.smartquery.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Portable bootstrap for the V2 orchestration metadata model.
 *
 * <p>The current project intentionally bootstraps its schema without Flyway.
 * DDL therefore stays conservative so it works with MySQL and DM8 compatible
 * mode. Tables are additive and do not change any V1 mining table.</p>
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class OrchestrationV2SchemaSeeder implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    private static final List<String> TABLE_DDLS = List.of(
        """
        CREATE TABLE IF NOT EXISTS sq_operator_definition (
            id BIGINT NOT NULL AUTO_INCREMENT,
            code VARCHAR(100) NOT NULL,
            name VARCHAR(200) NOT NULL,
            description TEXT,
            operator_type VARCHAR(20) NOT NULL,
            owner_user_id VARCHAR(64) NOT NULL,
            status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            deleted TINYINT NOT NULL DEFAULT 0,
            PRIMARY KEY (id),
            CONSTRAINT uk_operator_code_owner UNIQUE (code, owner_user_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_operator_version (
            id BIGINT NOT NULL AUTO_INCREMENT,
            operator_id BIGINT NOT NULL,
            version_no INT NOT NULL,
            status VARCHAR(30) NOT NULL DEFAULT 'CANDIDATE',
            content_hash VARCHAR(64) NOT NULL,
            input_schema TEXT NOT NULL,
            output_schema TEXT NOT NULL,
            parameter_schema TEXT NOT NULL,
            implementation_type VARCHAR(30) NOT NULL,
            implementation_payload TEXT NOT NULL,
            capability_requirements TEXT,
            validation_report TEXT,
            created_by_user_id VARCHAR(64) NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_operator_version_no UNIQUE (operator_id, version_no),
            CONSTRAINT uk_operator_version_hash UNIQUE (operator_id, content_hash)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_operator_version_approval (
            id BIGINT NOT NULL AUTO_INCREMENT,
            operator_id BIGINT NOT NULL,
            operator_version_id BIGINT NOT NULL,
            draft_type VARCHAR(30),
            draft_id BIGINT,
            status VARCHAR(30) NOT NULL,
            request_comment TEXT,
            requested_by_user_id VARCHAR(64) NOT NULL,
            reviewer_user_id VARCHAR(64),
            review_comment TEXT,
            reviewed_at DATETIME,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_operator_approval_version UNIQUE (operator_version_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_rule_primitive (
            id BIGINT NOT NULL AUTO_INCREMENT,
            code VARCHAR(80) NOT NULL,
            name VARCHAR(120) NOT NULL,
            category VARCHAR(50) NOT NULL,
            description TEXT,
            parameter_schema TEXT NOT NULL,
            executor_type VARCHAR(30) NOT NULL DEFAULT 'RULE_DSL',
            enabled TINYINT NOT NULL DEFAULT 1,
            version_no INT NOT NULL DEFAULT 1,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_rule_primitive_code UNIQUE (code)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_rule_draft (
            id BIGINT NOT NULL AUTO_INCREMENT,
            operator_id BIGINT NOT NULL,
            conversation_id BIGINT,
            based_on_version_id BIGINT,
            instruction_text TEXT NOT NULL,
            source_language VARCHAR(30) NOT NULL,
            entrypoint VARCHAR(120) NOT NULL,
            source_code TEXT NOT NULL,
            input_schema TEXT NOT NULL,
            output_schema TEXT NOT NULL,
            parameter_schema TEXT NOT NULL,
            test_cases TEXT NOT NULL,
            explanation TEXT,
            status VARCHAR(30) NOT NULL,
            validation_report TEXT NOT NULL,
            candidate_version_id BIGINT,
            created_by_user_id VARCHAR(64) NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_output_draft (
            id BIGINT NOT NULL AUTO_INCREMENT,
            operator_id BIGINT NOT NULL,
            conversation_id BIGINT,
            based_on_version_id BIGINT,
            instruction_text TEXT NOT NULL,
            raw_spec TEXT NOT NULL,
            shaped_spec TEXT NOT NULL,
            input_schema TEXT NOT NULL,
            output_schema TEXT NOT NULL,
            parameter_schema TEXT NOT NULL,
            explanation TEXT,
            status VARCHAR(30) NOT NULL,
            shaping_report TEXT NOT NULL,
            preview_data TEXT NOT NULL,
            preview_report TEXT NOT NULL,
            candidate_version_id BIGINT,
            published_version_id BIGINT,
            created_by_user_id VARCHAR(64) NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_policy_draft (
            id BIGINT NOT NULL AUTO_INCREMENT,
            operator_id BIGINT NOT NULL,
            operator_type VARCHAR(20) NOT NULL,
            conversation_id BIGINT,
            based_on_version_id BIGINT,
            instruction_text TEXT NOT NULL,
            raw_spec TEXT NOT NULL,
            shaped_spec TEXT NOT NULL,
            input_schema TEXT NOT NULL,
            output_schema TEXT NOT NULL,
            parameter_schema TEXT NOT NULL,
            explanation TEXT,
            status VARCHAR(30) NOT NULL,
            shaping_report TEXT NOT NULL,
            preview_data TEXT NOT NULL,
            preview_report TEXT NOT NULL,
            candidate_version_id BIGINT,
            published_version_id BIGINT,
            created_by_user_id VARCHAR(64) NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_dependency_request (
            id BIGINT NOT NULL AUTO_INCREMENT,
            request_no VARCHAR(64) NOT NULL,
            dependency_type VARCHAR(40) NOT NULL,
            runtime_type VARCHAR(40) NOT NULL,
            dependency_name VARCHAR(200) NOT NULL,
            requested_version VARCHAR(120) NOT NULL,
            resolved_version VARCHAR(120),
            source_uri VARCHAR(1000),
            checksum_sha256 VARCHAR(64),
            license_name VARCHAR(200),
            license_decision VARCHAR(30),
            vulnerability_critical INT NOT NULL DEFAULT 0,
            vulnerability_high INT NOT NULL DEFAULT 0,
            source_verified TINYINT NOT NULL DEFAULT 0,
            reason TEXT,
            status VARCHAR(30) NOT NULL,
            owner_user_id VARCHAR(64) NOT NULL,
            review_comment TEXT,
            reviewed_by_user_id VARCHAR(64),
            reviewed_at DATETIME,
            runtime_profile_id BIGINT,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_dependency_request_no UNIQUE (request_no)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_runtime_profile (
            id BIGINT NOT NULL AUTO_INCREMENT,
            code VARCHAR(160) NOT NULL,
            name VARCHAR(240) NOT NULL,
            runtime_type VARCHAR(40) NOT NULL,
            image_ref VARCHAR(500) NOT NULL,
            image_digest VARCHAR(80) NOT NULL,
            dependency_lock TEXT NOT NULL,
            build_manifest TEXT NOT NULL,
            security_report TEXT NOT NULL,
            status VARCHAR(30) NOT NULL,
            default_profile TINYINT NOT NULL DEFAULT 0,
            base_profile_id BIGINT,
            created_by_user_id VARCHAR(64) NOT NULL,
            approved_by_user_id VARCHAR(64) NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_runtime_profile_code UNIQUE (code),
            CONSTRAINT uk_runtime_image_digest UNIQUE (runtime_type, image_digest)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_runtime_dependency (
            id BIGINT NOT NULL AUTO_INCREMENT,
            runtime_profile_id BIGINT NOT NULL,
            request_id BIGINT,
            dependency_type VARCHAR(40) NOT NULL,
            dependency_name VARCHAR(200) NOT NULL,
            dependency_version VARCHAR(120) NOT NULL,
            source_uri VARCHAR(1000),
            checksum_sha256 VARCHAR(64),
            license_name VARCHAR(200),
            status VARCHAR(30) NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_runtime_dependency UNIQUE (runtime_profile_id, dependency_type, dependency_name)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_runtime_build_job (
            id BIGINT NOT NULL AUTO_INCREMENT,
            job_no VARCHAR(80) NOT NULL,
            dependency_request_id BIGINT NOT NULL,
            runtime_type VARCHAR(40) NOT NULL,
            base_profile_id BIGINT,
            build_spec TEXT NOT NULL,
            status VARCHAR(30) NOT NULL,
            attempt_no INT NOT NULL DEFAULT 0,
            max_attempts INT NOT NULL DEFAULT 3,
            worker_id VARCHAR(160),
            lease_token_hash VARCHAR(64),
            lease_expires_at DATETIME,
            started_at DATETIME,
            completed_at DATETIME,
            result_manifest TEXT,
            revalidation_report TEXT,
            error_code VARCHAR(100),
            error_message TEXT,
            runtime_profile_id BIGINT,
            requested_by_user_id VARCHAR(64) NOT NULL,
            approved_by_user_id VARCHAR(64) NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_runtime_build_job_no UNIQUE (job_no),
            CONSTRAINT uk_runtime_build_request UNIQUE (dependency_request_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_runtime_build_nonce (
            id BIGINT NOT NULL AUTO_INCREMENT,
            nonce_hash VARCHAR(64) NOT NULL,
            expires_at DATETIME NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_runtime_build_nonce UNIQUE (nonce_hash)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_operator_version_runtime (
            id BIGINT NOT NULL AUTO_INCREMENT,
            operator_version_id BIGINT NOT NULL,
            runtime_profile_id BIGINT NOT NULL,
            runtime_type VARCHAR(40) NOT NULL,
            image_digest VARCHAR(80) NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_operator_version_runtime UNIQUE (operator_version_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_draft_dependency (
            id BIGINT NOT NULL AUTO_INCREMENT,
            draft_type VARCHAR(30) NOT NULL,
            draft_id BIGINT NOT NULL,
            request_id BIGINT,
            dependency_type VARCHAR(40) NOT NULL,
            dependency_name VARCHAR(200) NOT NULL,
            version_constraint VARCHAR(120),
            status VARCHAR(30) NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_draft_dependency UNIQUE (draft_type, draft_id, dependency_type, dependency_name)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_flow_definition (
            id BIGINT NOT NULL AUTO_INCREMENT,
            code VARCHAR(100) NOT NULL,
            name VARCHAR(200) NOT NULL,
            description TEXT,
            owner_user_id VARCHAR(64) NOT NULL,
            status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            deleted TINYINT NOT NULL DEFAULT 0,
            PRIMARY KEY (id),
            CONSTRAINT uk_flow_code_owner UNIQUE (code, owner_user_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_flow_version (
            id BIGINT NOT NULL AUTO_INCREMENT,
            flow_id BIGINT NOT NULL,
            version_no INT NOT NULL,
            status VARCHAR(30) NOT NULL DEFAULT 'CANDIDATE',
            content_hash VARCHAR(64) NOT NULL,
            nodes TEXT NOT NULL,
            edges TEXT NOT NULL,
            parameter_mappings TEXT,
            validation_report TEXT NOT NULL,
            created_by_user_id VARCHAR(64) NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_flow_version_no UNIQUE (flow_id, version_no),
            CONSTRAINT uk_flow_version_hash UNIQUE (flow_id, content_hash)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_orchestration_run (
            id BIGINT NOT NULL AUTO_INCREMENT,
            flow_version_id BIGINT NOT NULL,
            owner_user_id VARCHAR(64) NOT NULL,
            actor_role VARCHAR(30),
            trigger_type VARCHAR(30) NOT NULL,
            run_mode VARCHAR(30) NOT NULL,
            status VARCHAR(30) NOT NULL,
            input_snapshot TEXT,
            output_summary TEXT,
            error_message TEXT,
            lease_owner VARCHAR(120),
            lease_token VARCHAR(64),
            lease_expires_at DATETIME,
            heartbeat_at DATETIME,
            attempt_no INT NOT NULL DEFAULT 0,
            recovery_count INT NOT NULL DEFAULT 0,
            cancel_requested_at DATETIME,
            cancel_requested_by VARCHAR(64),
            started_at DATETIME,
            finished_at DATETIME,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_node_run (
            id BIGINT NOT NULL AUTO_INCREMENT,
            run_id BIGINT NOT NULL,
            node_id VARCHAR(100) NOT NULL,
            operator_version_id BIGINT NOT NULL,
            status VARCHAR(30) NOT NULL,
            input_hash VARCHAR(64),
            output_hash VARCHAR(64),
            output_summary TEXT,
            execution_log TEXT,
            error_message TEXT,
            execution_time_ms BIGINT,
            attempt_no INT NOT NULL DEFAULT 0,
            lease_token VARCHAR(64),
            timeout_seconds INT,
            started_at DATETIME,
            finished_at DATETIME,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_node_run UNIQUE (run_id, node_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_node_run_snapshot (
            id BIGINT NOT NULL AUTO_INCREMENT,
            node_run_id BIGINT NOT NULL,
            run_id BIGINT NOT NULL,
            flow_version_id BIGINT NOT NULL,
            flow_content_hash VARCHAR(64) NOT NULL,
            node_id VARCHAR(100) NOT NULL,
            operator_version_id BIGINT NOT NULL,
            operator_version_content_hash VARCHAR(64) NOT NULL,
            operator_type VARCHAR(20) NOT NULL,
            implementation_type VARCHAR(40) NOT NULL,
            runtime_profile_id BIGINT NOT NULL,
            runtime_image_digest VARCHAR(80) NOT NULL,
            input_hash VARCHAR(64) NOT NULL,
            output_hash VARCHAR(64),
            attempt_no INT NOT NULL,
            lease_token VARCHAR(64) NOT NULL,
            status VARCHAR(30) NOT NULL,
            snapshot_bytes BIGINT NOT NULL DEFAULT 0,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_node_run_snapshot UNIQUE (node_run_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_node_run_snapshot_chunk (
            id BIGINT NOT NULL AUTO_INCREMENT,
            snapshot_id BIGINT NOT NULL,
            attempt_no INT NOT NULL,
            payload_kind VARCHAR(40) NOT NULL,
            chunk_index INT NOT NULL,
            payload_text TEXT NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_node_snapshot_chunk UNIQUE (snapshot_id, attempt_no, payload_kind, chunk_index)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_node_replay (
            id BIGINT NOT NULL AUTO_INCREMENT,
            replay_no VARCHAR(80) NOT NULL,
            source_run_id BIGINT NOT NULL,
            source_node_run_id BIGINT NOT NULL,
            snapshot_id BIGINT NOT NULL,
            flow_version_id BIGINT NOT NULL,
            flow_content_hash VARCHAR(64) NOT NULL,
            node_id VARCHAR(100) NOT NULL,
            operator_version_id BIGINT NOT NULL,
            operator_version_content_hash VARCHAR(64) NOT NULL,
            runtime_profile_id BIGINT NOT NULL,
            runtime_image_digest VARCHAR(80) NOT NULL,
            input_hash VARCHAR(64) NOT NULL,
            expected_output_hash VARCHAR(64),
            status VARCHAR(30) NOT NULL,
            attempt_no INT NOT NULL DEFAULT 0,
            timeout_seconds INT NOT NULL,
            lease_token VARCHAR(64),
            lease_expires_at DATETIME,
            owner_user_id VARCHAR(64) NOT NULL,
            actor_role VARCHAR(30) NOT NULL,
            output_hash VARCHAR(64),
            archive_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
            payload_bytes BIGINT NOT NULL DEFAULT 0,
            usage_accounted TINYINT NOT NULL DEFAULT 0,
            retention_until DATETIME,
            archived_at DATETIME,
            output_summary TEXT,
            diff_summary TEXT,
            execution_log TEXT,
            error_message TEXT,
            execution_time_ms BIGINT,
            started_at DATETIME,
            finished_at DATETIME,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_node_replay_no UNIQUE (replay_no)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_node_replay_chunk (
            id BIGINT NOT NULL AUTO_INCREMENT,
            replay_id BIGINT NOT NULL,
            attempt_no INT NOT NULL,
            payload_kind VARCHAR(40) NOT NULL,
            chunk_index INT NOT NULL,
            payload_text TEXT NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_node_replay_chunk UNIQUE (replay_id, attempt_no, payload_kind, chunk_index)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_output_artifact (
            id BIGINT NOT NULL AUTO_INCREMENT,
            run_id BIGINT NOT NULL,
            node_run_id BIGINT NOT NULL,
            owner_user_id VARCHAR(64) NOT NULL,
            output_kind VARCHAR(30) NOT NULL,
            status VARCHAR(30) NOT NULL,
            query_index_status VARCHAR(30) NOT NULL DEFAULT 'LEGACY',
            archive_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
            payload_bytes BIGINT NOT NULL DEFAULT 0,
            usage_accounted TINYINT NOT NULL DEFAULT 0,
            retention_until DATETIME,
            archived_at DATETIME,
            content_spec TEXT NOT NULL,
            artifact_data TEXT,
            file_path VARCHAR(1000),
            mime_type VARCHAR(200),
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_output_artifact_row (
            id BIGINT NOT NULL AUTO_INCREMENT,
            artifact_id BIGINT NOT NULL,
            row_index INT NOT NULL,
            result_data TEXT NOT NULL,
            source_data TEXT NOT NULL,
            evidence_data TEXT NOT NULL,
            source_refs TEXT NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_output_artifact_row UNIQUE (artifact_id, row_index)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_output_artifact_cell (
            id BIGINT NOT NULL AUTO_INCREMENT,
            artifact_id BIGINT NOT NULL,
            row_index INT NOT NULL,
            field_path VARCHAR(300) NOT NULL,
            value_type VARCHAR(20) NOT NULL,
            text_value TEXT,
            text_sort_value VARCHAR(1000),
            number_value DECIMAL(38,10),
            boolean_value INT,
            value_hash VARCHAR(64) NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_output_artifact_cell UNIQUE (artifact_id, row_index, field_path)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_storage_policy (
            id BIGINT NOT NULL,
            output_retention_days INT NOT NULL DEFAULT 90,
            replay_retention_days INT NOT NULL DEFAULT 30,
            hot_quota_bytes_per_user BIGINT NOT NULL DEFAULT 1073741824,
            archive_quota_bytes_per_user BIGINT NOT NULL DEFAULT 5368709120,
            warning_percent INT NOT NULL DEFAULT 80,
            auto_archive_enabled TINYINT NOT NULL DEFAULT 1,
            updated_by_user_id VARCHAR(64),
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_storage_usage (
            owner_user_id VARCHAR(64) NOT NULL,
            hot_bytes BIGINT NOT NULL DEFAULT 0,
            archive_bytes BIGINT NOT NULL DEFAULT 0,
            output_hot_bytes BIGINT NOT NULL DEFAULT 0,
            replay_hot_bytes BIGINT NOT NULL DEFAULT 0,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (owner_user_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_archive_record (
            id BIGINT NOT NULL AUTO_INCREMENT,
            target_type VARCHAR(30) NOT NULL,
            target_id BIGINT NOT NULL,
            owner_user_id VARCHAR(64) NOT NULL,
            state VARCHAR(30) NOT NULL,
            payload_format VARCHAR(40) NOT NULL,
            original_bytes BIGINT NOT NULL,
            stored_bytes BIGINT NOT NULL,
            checksum VARCHAR(64) NOT NULL,
            chunk_count INT NOT NULL,
            reason VARCHAR(500),
            archived_by_user_id VARCHAR(64) NOT NULL,
            archived_at DATETIME NOT NULL,
            restored_by_user_id VARCHAR(64),
            restored_at DATETIME,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_archive_chunk (
            id BIGINT NOT NULL AUTO_INCREMENT,
            archive_id BIGINT NOT NULL,
            chunk_index INT NOT NULL,
            payload_text TEXT NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT uk_archive_chunk UNIQUE (archive_id, chunk_index)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_lead_source_snapshot (
            id BIGINT NOT NULL AUTO_INCREMENT,
            data_source_id BIGINT,
            source_table VARCHAR(200),
            primary_key_column VARCHAR(120),
            primary_key_value VARCHAR(500),
            snapshot_data TEXT NOT NULL,
            snapshot_hash VARCHAR(64) NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_lead (
            id BIGINT NOT NULL AUTO_INCREMENT,
            lead_no VARCHAR(64) NOT NULL,
            lead_type VARCHAR(100) NOT NULL,
            owner_user_id VARCHAR(64) NOT NULL,
            subject_type VARCHAR(80),
            subject_id VARCHAR(200),
            subject_name VARCHAR(300),
            decision_score DOUBLE,
            decision_level VARCHAR(30),
            decision_threshold DOUBLE,
            decision_result VARCHAR(50),
            flow_version_id BIGINT NOT NULL,
            run_id BIGINT,
            source_snapshot_id BIGINT,
            attributes_data TEXT,
            status VARCHAR(30) NOT NULL DEFAULT 'NEW',
            assignee_user_id VARCHAR(64),
            occurred_at DATETIME,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            deleted TINYINT NOT NULL DEFAULT 0,
            PRIMARY KEY (id),
            CONSTRAINT uk_lead_no UNIQUE (lead_no)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_lead_evidence (
            id BIGINT NOT NULL AUTO_INCREMENT,
            lead_id BIGINT NOT NULL,
            node_run_id BIGINT,
            operator_version_id BIGINT,
            evidence_kind VARCHAR(40) NOT NULL,
            evidence_name VARCHAR(200),
            field_name VARCHAR(160),
            actual_value TEXT,
            condition_expression TEXT,
            contribution DOUBLE,
            snippet TEXT,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sq_lead_status_history (
            id BIGINT NOT NULL AUTO_INCREMENT,
            lead_id BIGINT NOT NULL,
            from_status VARCHAR(30),
            to_status VARCHAR(30) NOT NULL,
            actor_user_id VARCHAR(64) NOT NULL,
            comment_text TEXT,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id)
        )
        """
    );

    /** Additive upgrades for installations whose V2 tables already exist. */
    private static final List<String> COLUMN_DDLS = List.of(
        "ALTER TABLE sq_orchestration_run ADD COLUMN actor_role VARCHAR(30)",
        "ALTER TABLE sq_orchestration_run ADD COLUMN lease_owner VARCHAR(120)",
        "ALTER TABLE sq_orchestration_run ADD COLUMN lease_token VARCHAR(64)",
        "ALTER TABLE sq_orchestration_run ADD COLUMN lease_expires_at DATETIME",
        "ALTER TABLE sq_orchestration_run ADD COLUMN heartbeat_at DATETIME",
        "ALTER TABLE sq_orchestration_run ADD COLUMN attempt_no INT DEFAULT 0 NOT NULL",
        "ALTER TABLE sq_orchestration_run ADD COLUMN recovery_count INT DEFAULT 0 NOT NULL",
        "ALTER TABLE sq_orchestration_run ADD COLUMN cancel_requested_at DATETIME",
        "ALTER TABLE sq_orchestration_run ADD COLUMN cancel_requested_by VARCHAR(64)",
        "ALTER TABLE sq_node_run ADD COLUMN attempt_no INT DEFAULT 0 NOT NULL",
        "ALTER TABLE sq_node_run ADD COLUMN lease_token VARCHAR(64)",
        "ALTER TABLE sq_node_run ADD COLUMN timeout_seconds INT",
        "ALTER TABLE sq_output_artifact ADD COLUMN query_index_status VARCHAR(30) DEFAULT 'LEGACY' NOT NULL",
        "ALTER TABLE sq_output_artifact ADD COLUMN archive_status VARCHAR(30) DEFAULT 'ACTIVE' NOT NULL",
        "ALTER TABLE sq_output_artifact ADD COLUMN payload_bytes BIGINT DEFAULT 0 NOT NULL",
        "ALTER TABLE sq_output_artifact ADD COLUMN usage_accounted TINYINT DEFAULT 0 NOT NULL",
        "ALTER TABLE sq_output_artifact ADD COLUMN retention_until DATETIME",
        "ALTER TABLE sq_output_artifact ADD COLUMN archived_at DATETIME",
        "ALTER TABLE sq_node_replay ADD COLUMN archive_status VARCHAR(30) DEFAULT 'ACTIVE' NOT NULL",
        "ALTER TABLE sq_node_replay ADD COLUMN payload_bytes BIGINT DEFAULT 0 NOT NULL",
        "ALTER TABLE sq_node_replay ADD COLUMN usage_accounted TINYINT DEFAULT 0 NOT NULL",
        "ALTER TABLE sq_node_replay ADD COLUMN retention_until DATETIME",
        "ALTER TABLE sq_node_replay ADD COLUMN archived_at DATETIME",
        "ALTER TABLE sq_rule_draft ADD COLUMN candidate_version_id BIGINT",
        "ALTER TABLE sq_output_draft ADD COLUMN candidate_version_id BIGINT",
        "ALTER TABLE sq_policy_draft ADD COLUMN candidate_version_id BIGINT"
    );

    private static final List<String> INDEX_DDLS = List.of(
        "CREATE INDEX idx_operator_owner ON sq_operator_definition(owner_user_id, deleted)",
        "CREATE INDEX idx_operator_version_operator ON sq_operator_version(operator_id, version_no)",
        "CREATE INDEX idx_operator_approval_status ON sq_operator_version_approval(status, created_at)",
        "CREATE UNIQUE INDEX uk_operator_approval_version ON sq_operator_version_approval(operator_version_id)",
        "CREATE INDEX idx_rule_draft_operator ON sq_rule_draft(operator_id, created_at)",
        "CREATE INDEX idx_output_draft_operator ON sq_output_draft(operator_id, created_at)",
        "CREATE INDEX idx_policy_draft_operator ON sq_policy_draft(operator_id, created_at)",
        "CREATE INDEX idx_dependency_owner_status ON sq_dependency_request(owner_user_id, status, created_at)",
        "CREATE INDEX idx_runtime_profile_type ON sq_runtime_profile(runtime_type, status, default_profile)",
        "CREATE INDEX idx_runtime_dependency_profile ON sq_runtime_dependency(runtime_profile_id)",
        "CREATE INDEX idx_runtime_build_status ON sq_runtime_build_job(status, lease_expires_at, created_at)",
        "CREATE INDEX idx_runtime_build_owner ON sq_runtime_build_job(requested_by_user_id, created_at)",
        "CREATE INDEX idx_runtime_build_nonce_expiry ON sq_runtime_build_nonce(expires_at)",
        "CREATE INDEX idx_draft_dependency_draft ON sq_draft_dependency(draft_type, draft_id)",
        "CREATE INDEX idx_flow_owner ON sq_flow_definition(owner_user_id, deleted)",
        "CREATE INDEX idx_flow_version_flow ON sq_flow_version(flow_id, version_no)",
        "CREATE INDEX idx_run_flow_status ON sq_orchestration_run(flow_version_id, status)",
        "CREATE INDEX idx_run_lease ON sq_orchestration_run(status, lease_expires_at)",
        "CREATE INDEX idx_node_run_run ON sq_node_run(run_id)",
        "CREATE INDEX idx_node_snapshot_run ON sq_node_run_snapshot(run_id, node_id)",
        "CREATE INDEX idx_node_snapshot_chunk ON sq_node_run_snapshot_chunk(snapshot_id, attempt_no, payload_kind)",
        "CREATE INDEX idx_node_replay_source ON sq_node_replay(source_run_id, source_node_run_id, created_at)",
        "CREATE INDEX idx_node_replay_status ON sq_node_replay(status, lease_expires_at)",
        "CREATE INDEX idx_node_replay_chunk ON sq_node_replay_chunk(replay_id, attempt_no, payload_kind)",
        "CREATE INDEX idx_output_artifact_run ON sq_output_artifact(run_id, output_kind)",
        "CREATE INDEX idx_output_row_artifact ON sq_output_artifact_row(artifact_id, row_index)",
        "CREATE INDEX idx_output_cell_field ON sq_output_artifact_cell(artifact_id, field_path, row_index)",
        "CREATE INDEX idx_output_cell_hash ON sq_output_artifact_cell(artifact_id, field_path, value_hash, row_index)",
        "CREATE INDEX idx_output_cell_number ON sq_output_artifact_cell(artifact_id, field_path, number_value, row_index)",
        "CREATE INDEX idx_output_cell_boolean ON sq_output_artifact_cell(artifact_id, field_path, boolean_value, row_index)",
        "CREATE INDEX idx_output_retention ON sq_output_artifact(archive_status, retention_until)",
        "CREATE INDEX idx_replay_retention ON sq_node_replay(archive_status, retention_until)",
        "CREATE INDEX idx_archive_owner_state ON sq_archive_record(owner_user_id, state, archived_at)",
        "CREATE INDEX idx_archive_chunk_record ON sq_archive_chunk(archive_id, chunk_index)",
        "CREATE INDEX idx_lead_owner_status ON sq_lead(owner_user_id, status, created_at)",
        "CREATE INDEX idx_lead_flow ON sq_lead(flow_version_id, created_at)",
        "CREATE INDEX idx_lead_evidence_lead ON sq_lead_evidence(lead_id)"
    );

    @Override
    public void run(String... args) {
        for (String ddl : TABLE_DDLS) {
            jdbcTemplate.execute(ddl);
        }
        for (String ddl : COLUMN_DDLS) {
            try {
                jdbcTemplate.execute(ddl);
            } catch (Exception ignored) {
                // Column already exists. MySQL and DM8 use different duplicate-column codes.
            }
        }
        for (String ddl : INDEX_DDLS) {
            try {
                jdbcTemplate.execute(ddl);
            } catch (Exception ignored) {
                // Index already exists. Both supported databases report this differently.
            }
        }
        seedRulePrimitives();
        seedSystemOutputOperator();
        seedRuntimeProfiles();
        bindExistingOperatorVersions();
        log.info("[ORCHESTRATION-V2] schema ready; operator, dependency and runtime catalogs initialized");
    }

    private void seedRulePrimitives() {
        List<RuleSeed> seeds = List.of(
            new RuleSeed("filter", "条件过滤", "condition", "按布尔条件保留记录", schema("expression")),
            new RuleSeed("compare", "字段比较", "condition", "字段与常量或字段之间比较", schema("field", "operator", "value")),
            new RuleSeed("text_match", "文本匹配", "text", "关键词、正则或模糊文本匹配", schema("field", "mode", "keywords")),
            new RuleSeed("derive", "派生计算", "calculation", "基于安全表达式生成派生字段", schema("name", "expression")),
            new RuleSeed("group_by", "分组", "aggregation", "按一个或多个字段分组", schema("fields")),
            new RuleSeed("aggregate", "聚合统计", "aggregation", "count、sum、avg、min、max及去重计数", schema("metrics")),
            new RuleSeed("time_window", "时间窗口", "temporal", "自然周期或滚动时间窗口", schema("timeField", "range")),
            new RuleSeed("lookup", "关联查找", "relation", "受权数据集关联和字典映射", schema("source", "keys")),
            new RuleSeed("rank", "排序排名", "aggregation", "排名、TopN和百分位", schema("orderBy")),
            new RuleSeed("sequence", "事件序列", "temporal", "事件先后、连续出现和状态迁移", schema("events", "timeField")),
            new RuleSeed("threshold", "阈值分级", "decision", "根据表达式命中或划分风险等级", schema("expression")),
            new RuleSeed("lead_output", "线索输出", "output", "将规则结果映射为标准线索契约", schema("leadType", "subjectMapping"))
        );
        for (RuleSeed seed : seeds) {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sq_rule_primitive WHERE code = ?", Integer.class, seed.code());
            if (count != null && count > 0) continue;
            jdbcTemplate.update("""
                INSERT INTO sq_rule_primitive
                  (code, name, category, description, parameter_schema, executor_type,
                   enabled, version_no, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'RULE_DSL', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, seed.code(), seed.name(), seed.category(), seed.description(), seed.schema());
        }
    }

    private static String schema(String... required) {
        return "{\"type\":\"object\",\"required\":[\""
            + String.join("\",\"", required) + "\"]}";
    }

    private void seedSystemOutputOperator() {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM sq_operator_definition
            WHERE code = 'system_lead_output' AND owner_user_id = 'SYSTEM'
            """, Integer.class);
        if (count == null || count == 0) {
            jdbcTemplate.update("""
                INSERT INTO sq_operator_definition
                  (code, name, description, operator_type, owner_user_id, status,
                   created_at, updated_at, deleted)
                VALUES ('system_lead_output', '系统线索输出', '平台提供的标准线索输出算子（按需使用）',
                        'OUTPUT', 'SYSTEM', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """);
        }
        jdbcTemplate.update("""
            UPDATE sq_operator_definition
            SET description = '平台提供的标准线索输出算子（按需使用）', updated_at = CURRENT_TIMESTAMP
            WHERE code = 'system_lead_output' AND owner_user_id = 'SYSTEM'
            """);
        Long operatorId = jdbcTemplate.queryForObject("""
            SELECT id FROM sq_operator_definition
            WHERE code = 'system_lead_output' AND owner_user_id = 'SYSTEM'
            """, Long.class);
        Integer versionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sq_operator_version WHERE operator_id = ?", Integer.class, operatorId);
        if (versionCount != null && versionCount > 0) return;
        jdbcTemplate.update("""
            INSERT INTO sq_operator_version
              (operator_id, version_no, status, content_hash, input_schema, output_schema,
               parameter_schema, implementation_type, implementation_payload,
               capability_requirements, validation_report, created_by_user_id, created_at)
            VALUES (?, 1, 'PUBLISHED',
                    '0000000000000000000000000000000000000000000000000000000000000001',
                    '{"type":"object","required":["records"]}',
                    '{"type":"object","required":["records","leadCount"]}',
                    '{}', 'OUTPUT_RENDERER',
                    '{"outputKind":"LEAD","contentSpec":{}}',
                    '["STANDARD_LEAD","SOURCE_LINEAGE"]',
                    '{"valid":true,"systemManaged":true}', 'SYSTEM', CURRENT_TIMESTAMP)
            """, operatorId);
    }

    private void seedRuntimeProfiles() {
        List<RuntimeSeed> profiles = List.of(
            new RuntimeSeed("data-connector-core", "内置数据连接器", "DATA_CONNECTOR",
                "builtin://data-connector", digest('1')),
            new RuntimeSeed("rule-python-core", "Python 规则沙箱", "RULE_PYTHON",
                "smart-query-python:latest", digest('2')),
            new RuntimeSeed("rule-dsl-core", "声明式规则运行时", "RULE_DSL",
                "builtin://rule-dsl", digest('3')),
            new RuntimeSeed("ml-model-core", "机器学习模型运行时", "ML_MODEL",
                "smart-query-python:latest", digest('4')),
            new RuntimeSeed("agent-gateway-core", "受控智能体网关", "AGENT_GATEWAY",
                "builtin://agent-gateway", digest('5')),
            new RuntimeSeed("output-renderer-core", "Java 声明式输出渲染器", "OUTPUT_RENDERER",
                "builtin://output-renderer", digest('6'))
        );
        for (RuntimeSeed profile : profiles) {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sq_runtime_profile WHERE code = ?", Integer.class, profile.code());
            if (count != null && count > 0) continue;
            jdbcTemplate.update("""
                INSERT INTO sq_runtime_profile
                  (code, name, runtime_type, image_ref, image_digest, dependency_lock,
                   build_manifest, security_report, status, default_profile, base_profile_id,
                   created_by_user_id, approved_by_user_id, created_at)
                VALUES (?, ?, ?, ?, ?, '[]',
                        '{"source":"SYSTEM_BASELINE","immutable":true}',
                        '{"sourceVerified":true,"licenseDecision":"APPROVED","critical":0,"high":0}',
                        'ACTIVE', 1, NULL, 'SYSTEM', 'SYSTEM', CURRENT_TIMESTAMP)
                """, profile.code(), profile.name(), profile.runtimeType(),
                profile.imageRef(), profile.imageDigest());
        }
    }

    private void bindExistingOperatorVersions() {
        List<java.util.Map<String, Object>> versions = jdbcTemplate.queryForList("""
            SELECT v.id AS version_id, d.operator_type AS operator_type,
                   v.implementation_type AS implementation_type
            FROM sq_operator_version v
            JOIN sq_operator_definition d ON d.id = v.operator_id
            WHERE NOT EXISTS (
                SELECT 1 FROM sq_operator_version_runtime b WHERE b.operator_version_id = v.id
            )
            """);
        for (java.util.Map<String, Object> row : versions) {
            Long versionId = ((Number) value(row, "version_id")).longValue();
            String operatorType = String.valueOf(value(row, "operator_type"));
            String implementationType = String.valueOf(value(row, "implementation_type"));
            String runtimeType = runtimeType(operatorType, implementationType);
            List<java.util.Map<String, Object>> profiles = jdbcTemplate.queryForList("""
                SELECT id, image_digest FROM sq_runtime_profile
                WHERE runtime_type = ? AND status = 'ACTIVE' AND default_profile = 1
                """, runtimeType);
            if (profiles.isEmpty()) throw new IllegalStateException("缺少默认运行时: " + runtimeType);
            java.util.Map<String, Object> profile = profiles.get(0);
            jdbcTemplate.update("""
                INSERT INTO sq_operator_version_runtime
                  (operator_version_id, runtime_profile_id, runtime_type, image_digest, created_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, versionId, ((Number) value(profile, "id")).longValue(), runtimeType,
                String.valueOf(value(profile, "image_digest")));
        }
    }

    private Object value(java.util.Map<String, Object> row, String name) {
        if (row.containsKey(name)) return row.get(name);
        if (row.containsKey(name.toUpperCase())) return row.get(name.toUpperCase());
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
            .map(java.util.Map.Entry::getValue).findFirst().orElse(null);
    }

    private String runtimeType(String operatorType, String implementationType) {
        if ("RULE".equals(operatorType) && "SANDBOX_EXTENSION".equals(implementationType)) return "RULE_PYTHON";
        if ("RULE".equals(operatorType)) return "RULE_DSL";
        return switch (operatorType) {
            case "DATA" -> "DATA_CONNECTOR";
            case "ML" -> "ML_MODEL";
            case "AGENT" -> "AGENT_GATEWAY";
            case "OUTPUT" -> "OUTPUT_RENDERER";
            default -> throw new IllegalArgumentException("未知算子类型: " + operatorType);
        };
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record RuleSeed(String code, String name, String category,
                            String description, String schema) {}
    private record RuntimeSeed(String code, String name, String runtimeType,
                               String imageRef, String imageDigest) {}
}
