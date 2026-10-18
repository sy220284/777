package com.labteto.dshmobile.uipack.zhaoxi.scenes

/** UI-side constraints mirror the existing Agent/Model Owners: values must not silently clamp. */
internal enum class ZxRuntimeLimitField { MAIN_STEPS, SUBAGENT_STEPS, MODEL_ATTEMPTS }

internal fun zxRuntimeLimitError(field: ZxRuntimeLimitField, raw: String): String? {
    val value = raw.trim().toIntOrNull()
    val (range, name) = when (field) {
        ZxRuntimeLimitField.MAIN_STEPS -> (4..512) to "主任务步数"
        ZxRuntimeLimitField.SUBAGENT_STEPS -> (1..512) to "子任务步数"
        ZxRuntimeLimitField.MODEL_ATTEMPTS -> (1..5) to "模型尝试次数"
    }
    return if (value == null || value !in range) {
        "${name}须为 ${range.first}–${range.last} 之间的整数"
    } else null
}

/** Model Owner constrains persisted temperature ceilings to 1.0–2.0. */
internal fun zxTemperatureCeilingError(raw: String): String? {
    val value = raw.trim().toDoubleOrNull()
    return if (value == null || !value.isFinite() || value !in 1.0..2.0) {
        "温度上限须为 1.0–2.0 之间的数值"
    } else null
}

internal fun zxTimeOfDayMinutes(raw: String): Int? {
    val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(raw.trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}

/** Overnight windows are permitted; identical endpoints are ambiguous and rejected. */
internal fun zxQuietHoursError(start: String, end: String): String? {
    val from = zxTimeOfDayMinutes(start)
    val to = zxTimeOfDayMinutes(end)
    return when {
        from == null || to == null -> "请输入正确时间（00:00–23:59）"
        from == to -> "开始时间与结束时间不能相同"
        else -> null
    }
}
