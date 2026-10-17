package com.waytoconsistency

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

object HealthConnectIntegration {
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class)
    )

    fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

    suspend fun summary(context: Context): JSONObject {
        val status = sdkStatus(context)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            return JSONObject().put("status", if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) "update_required" else "unavailable")
        }
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(permissions)) {
            return JSONObject().put("status", "permission_required").put("granted", granted.size).put("required", permissions.size)
        }
        val now = Instant.now()
        val dayStart = now.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val steps = client.aggregate(
            AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(dayStart, now))
        )[StepsRecord.COUNT_TOTAL] ?: 0L
        val heart = client.readRecords(
            ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(1)), now), ascendingOrder = false, pageSize = 1)
        ).records.firstOrNull()?.samples?.lastOrNull()?.beatsPerMinute
        val sleep = client.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(2)), now), ascendingOrder = false, pageSize = 1)
        ).records.firstOrNull()?.let { Duration.between(it.startTime, it.endTime).toMinutes() }
        return JSONObject()
            .put("status", "connected")
            .put("steps", steps)
            .put("heartRate", heart ?: JSONObject.NULL)
            .put("sleepMinutes", sleep ?: JSONObject.NULL)
            .put("updatedAt", now.toString())
    }

    suspend fun writeRun(context: Context, runId: String, start: Instant, end: Instant, distanceMeters: Double): Boolean {
        if (sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE || end <= start) return false
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        val needed = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class)
        )
        if (!granted.containsAll(needed)) return false
        val zone = ZoneId.systemDefault().rules.getOffset(start)
        val metadata = Metadata.manualEntry(clientRecordId = "wtc-run-$runId")
        val records = mutableListOf<androidx.health.connect.client.records.Record>(
            ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = zone,
                endTime = end,
                endZoneOffset = ZoneId.systemDefault().rules.getOffset(end),
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                title = "Way to Consistency run",
                metadata = metadata
            )
        )
        if (distanceMeters > 0) records += DistanceRecord(
            startTime = start,
            startZoneOffset = zone,
            endTime = end,
            endZoneOffset = ZoneId.systemDefault().rules.getOffset(end),
            distance = Length.meters(distanceMeters),
            metadata = Metadata.manualEntry(clientRecordId = "wtc-run-$runId-distance")
        )
        client.insertRecords(records)
        return true
    }
}
