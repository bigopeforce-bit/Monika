package com.bigopeforcebit.monika

import com.bigopeforcebit.monika.core.action.ActionExecutor
import com.bigopeforcebit.monika.core.action.ActionRecorder
import com.bigopeforcebit.monika.core.action.Origin
import com.bigopeforcebit.monika.core.action.execution
import com.bigopeforcebit.monika.core.guard.OperationGuards
import com.bigopeforcebit.monika.core.model.Part
import com.bigopeforcebit.monika.core.record.RunSink
import com.bigopeforcebit.monika.core.tool.ToolDispatcher
import com.bigopeforcebit.monika.core.tool.ToolExecution

/** 设备测试里直接做一个工具调用:和产品一样经执行入口(不配确认关卡,不等锁屏)。 */
suspend fun ToolDispatcher.runAction(call: Part.ToolCall): ToolExecution =
    ActionExecutor(this, OperationGuards(), null, gate = {}, recorder = ActionRecorder(RunSink.NONE)).run(call, Origin.Model(0)).execution()
