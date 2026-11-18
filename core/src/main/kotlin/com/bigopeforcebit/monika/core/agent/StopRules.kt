package com.bigopeforcebit.monika.core.agent

import com.bigopeforcebit.monika.core.net.ProxyFailure
import com.bigopeforcebit.monika.core.guard.GuardVerdict
import com.bigopeforcebit.monika.core.model.ModelError
import com.bigopeforcebit.monika.core.tool.Outcome
import kotlinx.serialization.json.JsonPrimitive

/**
 * 停止规则:任务什么时候、以什么结局结束(放弃、守卫叫停、模型出错、没给出回答、步数用完)。办完不在这里:
 * 不带工具调用的回复就是回答,由循环核对后交出。纯函数;结局里给用户看的说明不发给模型。
 */
internal object StopRules {
    /** 一个动作执行完之后:放弃了就按它结束;否则守卫([stop],屏幕守卫或不动屏幕的工具的守卫)叫停了就停。都没有为 null,接着做。 */
    fun afterAction(outcome: Outcome?, stop: GuardVerdict?): TaskState? = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stop?.let(::guard)
    }

    /** 步数用完后的最后一轮调了工具(只给了 end_not_done):放弃按它结束,否则算步数用完。 */
    fun afterFinalTurn(outcome: Outcome?, maxSteps: Int): TaskState = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stepLimit(maxSteps)
    }

    fun stepLimit(maxSteps: Int) = TaskState.Failed("已达到 $maxSteps 步上限,任务没有完成。", FailKind.StepLimit)

    /** 收尾请求本身失败或仍没交出结果时，按触发收尾的原因落结局，不谎报步数用完。 */
    fun closeoutFailed(reason: Closeout, maxSteps: Int): TaskState.Failed = when (reason) {
        Closeout.StepLimit -> stepLimit(maxSteps)
        Closeout.Completed -> TaskState.Failed("任务进度已经完成,但模型没有给出结果。", FailKind.Model)
        Closeout.NoNewData -> TaskState.Failed("连续 ${NewDataTracker.LIMIT} 次没有读到新数据,任务已停止。", FailKind.Guard)
    }

    /** 提醒过一次,模型仍然回了空话(或又被输出上限截断)。 */
    fun noAnswer() = TaskState.Failed("模型停止了操作,没有给出回答。", FailKind.Model)

    /** 退回过一次,模型的工具调用又以文字形式回来了(见 [LeakedToolCall]):不把原样的标记当回答交出。 */
    fun toolCallAsText() = TaskState.Failed("The model's tool call was not recognized by the service and came back as plain text, so it could not be executed.", FailKind.Model)

    /** 模型调用失败(重试过仍不行,或重试也没用)。 */
    fun modelFailed(e: Throwable): TaskState.Failed = when (e) {
        is ModelError.TaskBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.DailyBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.ProactiveBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.RateLimited -> TaskState.Failed(userReason(e), FailKind.Model, FailureCode.ModelRateLimited)
        is ModelError -> TaskState.Failed(userReason(e), FailKind.Model)
        else -> TaskState.Failed("Model call failed: ${e.message ?: e::class.simpleName}", FailKind.Model)
    }

    fun guard(v: GuardVerdict): TaskState.Failed {
        val treeBlind = v.evidence["treeBlind"] == JsonPrimitive(true)
        val why = when (v.code) {
            "repeating_action" -> "the same action kept repeating without any effect"
            "no_progress" -> if (treeBlind) "the screen tree did not expose an action target and the screen did not change after repeated actions, so retries were stopped"
                else "the screen did not change after several actions"
            "read_loop" -> "the page did not change and rereading it did not reveal the requested content"
            "too_many_failures" -> "several actions failed in a row"
            "sandbox_loop" -> "repeating the same command unchanged still failed"
            else -> "an operation safeguard was triggered"
        }
        return TaskState.Failed("Stopped safely (${v.code}): $why.", FailKind.Guard)
    }
}

internal fun userReason(e: ModelError): String = when (e) {
    is ModelError.Auth -> "The model service rejected the request: the API key is invalid or lacks permission. Check the API key in Settings."
    is ModelError.IllegalHeader -> "The API key contains invalid characters. In Settings > Models, paste only the key itself, with no extra text, line breaks, or emoji."
    is ModelError.QuotaExceeded -> "Your model account quota has been used up. Check your provider account balance or plan."
    is ModelError.TaskBudgetExceeded -> "This task reached its ${e.limit}-token budget limit and did not finish."
    is ModelError.DailyBudgetExceeded ->
        "Provider \"${e.providerName}\" has reached today's ${e.limit}-token budget limit, so the task did not finish. It resets after the local date changes."
    is ModelError.ProactiveBudgetExceeded -> "Today's budget for proactive features has been used up. It resets after the local date changes."
    is ModelError.ModelNotFound -> {
        val check = if (e.model.isEmpty()) "Check the model name and Base URL in Settings > Models."
            else "In Settings > Models, check the model name (use the full model ID; Gemini names look like gemini-flash-latest) or choose a preset."
        when {
            e.detail.isNotEmpty() ->
                (if (e.model.isEmpty()) "" else "When calling model \"${e.model}\", ") +
                    "the server replied: ${quoted(e.detail)}. The model name may be wrong, or the service may be temporarily unavailable. $check"
            e.model.isEmpty() -> "Model not found. $check"
            else -> "Model \"${e.model}\" does not exist at this provider. $check"
        }
    }
    is ModelError.ModelNotAvailable ->
        "This account does not have access to model \"${e.model.ifEmpty { "the current model" }}\" (not enabled or no permission). Switch models in Settings > Models or enable it in the provider dashboard."
    is ModelError.ToolsUnsupported ->
        "Model \"${e.model.ifEmpty { "the current model" }}\" does not support tool calls, which Monika needs to do work. Switch to a model that supports tools in Settings > Models."
    is ModelError.ContextTooLong -> "The conversation exceeds the model's context length. Use a model with a longer context or break the task into smaller steps."
    is ModelError.ContentRefused -> "The model refused to process this content. Adjust the task description or switch models."
    is ModelError.ReasoningEffortRejected ->
        "The model rejected the selected reasoning effort. Server reply: ${quoted(e.detail)}. In Settings > Models, set reasoning effort to Default or a level the service supports."
    is ModelError.RateLimited -> "The model service is rate-limiting requests even after retries. Try again later or check your account's rate limits."
    is ModelError.Network -> "Could not connect to the model service, even after retries. Check your network and Base URL."
    is ModelError.Proxy -> when (e.failure) {
        ProxyFailure.Unreachable ->
            "Cannot connect to the network proxy, or the proxy did not forward the request. Check that the proxy is running and that the address and port in Settings > Network proxy are correct; clear it if you do not need a proxy."
        ProxyFailure.AuthFailed -> "The network proxy rejected the username or password (HTTP 407). Check the credentials in the proxy address in Settings > Network proxy."
    }
    is ModelError.Server -> "Model service error (HTTP ${e.status}). Please try again later."
    is ModelError.StreamCut ->
        "The reply was cut off before it finished, even after retries. This is usually an unstable connection or a short idle timeout in a gateway or relay. Please try again."
    is ModelError.BadResponse -> "${e.message}. Check that the Base URL and API type match."
    is ModelError.ChatGptLimitReached ->
        "ChatGPT quota used up. Try again later. You can check usage in ChatGPT settings > Usage (chatgpt.com/settings/usage); Plus 5-hour limits are shared with other apps signed in with ChatGPT. You can also switch models."
    is ModelError.ChatGptNotEligible ->
        "This account cannot use ChatGPT credits in other apps (requires Plus or Pro, with region and account settings that allow it). ${if (e.detail.isEmpty()) "" else "Server note: ${quoted(e.detail)}."} You can switch models."
    is ModelError.SignInExpired -> "${e.account} sign-in has expired. Please sign in again in Settings > Models."
    is ModelError.AccountPlanNotAllowed ->
        "Your ${e.account} plan cannot use this endpoint (HTTP 403). ${if (e.detail.isEmpty()) "" else "Server note: ${quoted(e.detail)}."} You can switch to an API key for ${e.account} or choose another model in Settings > Models."
    is ModelError.ChainFailed -> {
        val trail = e.entries.joinToString("; ") { (name, err) ->
            "\"$name\"${chainErrorTag(err)}: ${err.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(80)}"
        }
        userReason(e.primary) + " This task tried every fallback model and all of them failed: $trail."
    }
}

/** 链失败记录里每个错误的短标签:认得出的写 HTTP 状态码,其余写故障名。 */
private fun chainErrorTag(e: ModelError): String = when (e) {
    is ModelError.Server -> " HTTP ${e.status}"
    is ModelError.RateLimited -> " HTTP 429"
    is ModelError.Auth -> " " + RetryPolicy.label(e)
    else -> " ${RetryPolicy.label(e)}"
}

/** 引在句中的服务端原话:去掉末尾一个句号(原话自带的),由引它的句子补上自己的「。」;省略号(截短的「…」、「...」)不动。 */
private fun quoted(detail: String): String =
    when {
        detail.endsWith("..") -> detail
        detail.endsWith(".") || detail.endsWith("。") -> detail.dropLast(1)
        else -> detail
    }
