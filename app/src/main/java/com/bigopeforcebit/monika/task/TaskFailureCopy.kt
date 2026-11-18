package com.bigopeforcebit.monika.task

import com.bigopeforcebit.monika.R
import com.bigopeforcebit.monika.core.agent.FailureCode
import com.bigopeforcebit.monika.core.agent.TaskState
import com.bigopeforcebit.monika.i18n.tr

/** Translate structured failures before publishing or saving the result. */
internal fun localizeTaskFailure(state: TaskState): TaskState {
    if (state !is TaskState.Failed) return state
    val reason = when (state.code) {
        FailureCode.ModelRateLimited -> tr(R.string.task_model_rate_limited)
        null -> return state
    }
    return state.copy(reason = reason)
}
