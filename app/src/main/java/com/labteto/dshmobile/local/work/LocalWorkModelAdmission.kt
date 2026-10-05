package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.model.LocalModelAdmissionPort

/** WorkFeature adapter for the shared model-admission contract. */
internal fun LocalWorkExecutionControl.asModelAdmissionPort(): LocalModelAdmissionPort =
    LocalModelAdmissionPort { request, block ->
        executeWithModelAdmission(
            control = this,
            routeFingerprint = request.routeFingerprint,
            model = request.model,
            baseUrl = request.baseUrl,
            contextWindowTokensOverride = request.contextWindowTokensOverride,
            messages = request.messages,
            tools = request.tools,
        ) {
            block()
        }
    }
