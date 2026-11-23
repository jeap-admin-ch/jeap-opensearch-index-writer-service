package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.maintenance;

// Field names of the job and task index mappings (maintenance-index-mappings/*.json) used in more than one place
final class MaintenanceFields {

    static final String JOB_ID = "job_id";
    static final String JOB_STATE = "job_state";
    static final String JOB_RESULT = "job_result";
    static final String MATERIALIZATION_STATE = "materialization_state";
    static final String STARTED_AT = "started_at";
    static final String COMPLETED_AT = "completed_at";
    static final String TASK_STATE = "task_state";
    static final String POSITION = "position";
    static final String ERROR = "error";

    private MaintenanceFields() {
    }
}
