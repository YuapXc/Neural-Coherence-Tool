package io.github.neuralcoherence.probe;

final class LiveUpdateContract {
    static final String MODULE_PACKAGE = "io.github.neuralcoherence.probe";
    static final String TARGET_PACKAGE = "com.linktech.arkradar";
    static final String TARGET_ACTIVITY = TARGET_PACKAGE + ".MainActivity";

    static final String ACTION_UPDATE = MODULE_PACKAGE + ".action.UPDATE_LIVE_STATUS";
    static final String ACTION_FINISH = MODULE_PACKAGE + ".action.FINISH_LIVE_STATUS";
    static final String ACTION_COMPLETE_TRANSITION =
            MODULE_PACKAGE + ".action.COMPLETE_LIVE_TRANSITION";
    static final String ACTION_STOP_CLICKED = MODULE_PACKAGE + ".action.STOP_CLICKED";
    static final String ACTION_STOP_TARGET = MODULE_PACKAGE + ".action.STOP_TARGET_INTERACTION";

    static final String EXTRA_STAGE = "stage";
    static final String EXTRA_CURRENT = "current";
    static final String EXTRA_TOTAL = "total";
    static final String EXTRA_SUCCESS = "success";
    static final String EXTRA_ALLOW_STOP = "allow_stop";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_SUMMARY = "summary";
    static final String EXTRA_DETAILS = "details";
    static final String EXTRA_COMPLETION_LABEL = "completion_label";
    static final String EXTRA_TRANSITION_TOKEN = "transition_token";

    static final long COMPLETION_PREVIEW_MILLIS = 60_000L;

    private LiveUpdateContract() {
    }
}
