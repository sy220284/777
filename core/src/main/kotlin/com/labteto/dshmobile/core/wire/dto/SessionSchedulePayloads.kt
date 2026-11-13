@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.serialization.InternalSerializationApi::class,
)

package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `schedule/change` payload — strict version-1 durable Schedule mutation union. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("operation")
sealed class ScheduleChangeData {
    @Serializable
    @SerialName("create")
    data class Create(
        @SerialName("version") val version: Int = 1,
        @SerialName("schedule") val schedule: ScheduleRecord,
    ) : ScheduleChangeData()

    @Serializable
    @SerialName("delete")
    data class Delete(
        @SerialName("version") val version: Int = 1,
        @SerialName("id") val id: String,
    ) : ScheduleChangeData()

    @Serializable
    @SerialName("dispatch")
    data class Dispatch(
        @SerialName("version") val version: Int = 1,
        @SerialName("id") val id: String,
        /** Wall-clock decision time for fixed-rate decisions. */
        @SerialName("acceptedAt") val acceptedAt: String? = null,
    ) : ScheduleChangeData()
}

/** The v1 durable reminder record union. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("kind")
sealed class ScheduleRecord {
    /** A delayed one-shot reminder. */
    @Serializable
    @SerialName("after")
    data class After(
        @SerialName("id") val id: String,
        @SerialName("prompt") val prompt: String,
        @SerialName("afterSeconds") val afterSeconds: Int,
        @SerialName("scheduledAt") val scheduledAt: String,
    ) : ScheduleRecord()

    /** An absolute one-shot reminder. */
    @Serializable
    @SerialName("at")
    data class At(
        @SerialName("id") val id: String,
        @SerialName("prompt") val prompt: String,
        @SerialName("scheduledAt") val scheduledAt: String,
    ) : ScheduleRecord()

    /** A fixed-rate recurring reminder. */
    @Serializable
    @SerialName("every")
    data class Every(
        @SerialName("id") val id: String,
        @SerialName("prompt") val prompt: String,
        @SerialName("everySeconds") val everySeconds: Int,
        @SerialName("scheduledAt") val scheduledAt: String,
    ) : ScheduleRecord()
}
