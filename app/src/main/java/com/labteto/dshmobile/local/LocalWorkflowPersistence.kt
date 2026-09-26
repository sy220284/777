package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.workflow.HarnessWorkflowCheckpoint
import com.labteto.dshmobile.harness.workflow.HarnessWorkflowMode
import com.labteto.dshmobile.harness.workflow.HarnessWorkflowTaskResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal const val LOCAL_WORKFLOW_CHECKPOINT_EVENT = "workflow/checkpoint"

internal fun encodeWorkflowCheckpoint(
    workflowId: String,
    checkpoint: HarnessWorkflowCheckpoint,
): JsonObject = buildJsonObject {
    put("workflow_id", workflowId)
    put("mode", checkpoint.mode.name.lowercase())
    put("tasks", JsonArray(checkpoint.tasks.map(::JsonPrimitive)))
    put("results", JsonArray(checkpoint.results.map { result ->
        buildJsonObject {
            put("index", result.index)
            put("task", result.task)
            result.output?.let { put("output", it.take(65_536)) }
            result.error?.let { put("error", it.take(4_000)) }
        }
    }))
}

internal fun decodeWorkflowCheckpoint(data: JsonObject): HarnessWorkflowCheckpoint? {
    val mode = (data["mode"] as? JsonPrimitive)?.contentOrNull
        ?.let { encoded -> HarnessWorkflowMode.entries.firstOrNull { it.name.equals(encoded, true) } }
        ?: return null
    val tasks = (data["tasks"] as? JsonArray)?.mapNotNull { element ->
        (element as? JsonPrimitive)?.contentOrNull
    } ?: return null
    if (tasks.isEmpty()) return null
    val results = (data["results"] as? JsonArray)?.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val index = (item["index"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        val task = (item["task"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        HarnessWorkflowTaskResult(
            index = index,
            task = task,
            output = (item["output"] as? JsonPrimitive)?.contentOrNull,
            error = (item["error"] as? JsonPrimitive)?.contentOrNull,
        )
    } ?: return null
    return HarnessWorkflowCheckpoint(mode = mode, tasks = tasks, results = results)
}
