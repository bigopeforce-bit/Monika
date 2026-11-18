package com.bigopeforcebit.monika.task

import com.bigopeforcebit.monika.core.agent.FailKind
import com.bigopeforcebit.monika.core.agent.FailureCode
import com.bigopeforcebit.monika.core.agent.TaskState
import com.bigopeforcebit.monika.i18n.ResFileTexts
import com.bigopeforcebit.monika.i18n.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TaskFailureCopyTest {
    @Test fun rateLimitUsesTheSelectedLanguage() {
        val previous = UiText.source
        try {
            val failure = TaskState.Failed("中文", FailKind.Model, FailureCode.ModelRateLimited)
            UiText.source = ResFileTexts("values-ar", Locale.forLanguageTag("ar"))
            val arabic = localizeTaskFailure(failure) as TaskState.Failed
            assertTrue(arabic.reason.contains("خدمة النموذج"))
            assertEquals(FailKind.Model, arabic.kind)
            assertEquals(FailureCode.ModelRateLimited, arabic.code)
            UiText.source = ResFileTexts.english
            assertTrue((localizeTaskFailure(failure) as TaskState.Failed).reason.contains("rate limit"))
            UiText.source = ResFileTexts.chinese
            assertTrue((localizeTaskFailure(failure) as TaskState.Failed).reason.contains("模型服务限流"))
        } finally {
            UiText.source = previous
        }
    }

    @Test fun unstructuredFailuresAndSuccessfulAnswersRemainUntouched() {
        val failure = TaskState.Failed("User-provided explanation", FailKind.GaveUp)
        val success = TaskState.Succeeded("answer")
        assertSame(failure, localizeTaskFailure(failure))
        assertSame(success, localizeTaskFailure(success))
    }
}
