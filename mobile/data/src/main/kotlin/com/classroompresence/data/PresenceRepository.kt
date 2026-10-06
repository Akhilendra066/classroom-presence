package com.classroompresence.data

import android.content.Context
import com.classroompresence.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class PresenceRepository(context: Context) {
    val dao = LocalStore.get(context).dao()
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("account", Context.MODE_PRIVATE)
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    var usedOfflineCache = false
        private set
    private val cloud = SupabaseCloud(app)
    val cloudConfigured: Boolean get() = cloud.configured
    var account: Account? = preferences.getString("account", null)?.let { runCatching { json.decodeFromString<Account>(it) }.getOrNull() }
        private set
    init {
        if (account?.demo == true || (account != null && preferences.getString("backend", null) != "supabase-v1")) {
            account = null; preferences.edit().remove("account").apply()
        }
    }
    fun remember(value: Account) { check(!value.demo) { "An institution account is required." }; account = value; preferences.edit().putString("account", json.encodeToString(value)).putString("backend", "supabase-v1").apply() }
    suspend fun login(email: String, password: String, role: String): Account {
        check(cloudConfigured) { "Supabase is not configured. See docs/supabase-setup.md." }
        return cloud.login(email, password, role).also(::remember)
    }
    suspend fun resetPassword(email: String) {
        check(cloudConfigured) { "Configure Supabase first." }
        cloud.resetPassword(email)
    }
    suspend fun setPasswordFromLink(link: String, password: String) { cloud.setPasswordFromLink(link, password) }
    fun logout() { cloud.clear(); account = null; preferences.edit().remove("account").apply() }
    private fun current(): Account = account ?: error("Please sign in.")
    private suspend fun call(name: String, payload: Map<String, Any?> = emptyMap()): String {
        val who = current()
        check(!who.demo && cloudConfigured) { "Sign in again to synchronize this account." }
        return cloud.call(who.uid, name, payload)
    }
    suspend fun classes(): List<ClassInfo> {
        val who = current()
        usedOfflineCache = false
        val key = "classes:${who.uid}"
        return try { json.decodeFromString<List<ClassInfo>>(call("listClasses")).also { dao.cache(CachedDocument(key, json.encodeToString(it))) } }
        catch (e: Exception) { usedOfflineCache = true; dao.cached(key)?.let { json.decodeFromString(it.json) } ?: throw e }
    }
    suspend fun sessions(): List<ClassSession> {
        val who = current()
        val key = "sessions:${who.uid}"
        return try { json.decodeFromString<List<ClassSession>>(call("listSessions")).also { items ->
            dao.cache(CachedDocument(key, json.encodeToString(items)))
            items.forEach { dao.cache(CachedDocument("session:${who.uid}:${it.id}", json.encodeToString(it))) }
        } } catch (e: Exception) { usedOfflineCache = true; dao.cached(key)?.let { json.decodeFromString(it.json) } ?: throw e }
    }
    suspend fun session(id: String): ClassSession {
        val who = current()
        return dao.cached("session:${who.uid}:$id")?.let { json.decodeFromString(it.json) } ?: sessions().first { it.id == id }
    }
    /** Poll small state records; immutable configuration/evidence is downloaded only once. */
    suspend fun refreshSessionStates(): List<ClassSession> {
        val who = current()
        val key = "sessions:${who.uid}"
        val saved = dao.cached(key)?.let { json.decodeFromString<List<ClassSession>>(it.json) } ?: return sessions()
        val byId = saved.associateBy { it.id }
        return try {
            usedOfflineCache = false
            val updates = json.parseToJsonElement(call("listSessionStates")).jsonArray
            if (updates.any { it.jsonObject["id"]!!.jsonPrimitive.content !in byId }) return sessions()
            updates.map { item ->
                val fields = item.jsonObject
                val id = fields["id"]!!.jsonPrimitive.content
                byId.getValue(id).copy(state = fields["state"]!!.jsonPrimitive.content,
                    endMs = fields["endMs"]!!.jsonPrimitive.long)
            }.also { items ->
                dao.cache(CachedDocument(key, json.encodeToString(items)))
                items.forEach { dao.cache(CachedDocument("session:${who.uid}:${it.id}", json.encodeToString(it))) }
            }
        } catch (e: Exception) { usedOfflineCache = true; saved }
    }
    suspend fun startSession(info: ClassInfo, timing: SessionTiming? = null): ClassSession {
        val who = current(); check(who.role == "TEACHER")
        timing?.validate()
        val payload = mutableMapOf<String, Any?>("classId" to info.id)
        timing?.let { payload["durationMinutes"] = it.durationMinutes; payload["minimumPresenceMinutes"] = it.minimumPresenceMinutes }
        val created = json.decodeFromString<ClassSession>(call("startSession", payload))
        check(!created.demo) { "An institution session is required." }
        dao.cache(CachedDocument("session:${who.uid}:${created.id}", json.encodeToString(created)))
        return created
    }
    suspend fun endSession(session: ClassSession) {
        check(current().role == "TEACHER" && !session.demo)
        call("endSession", mapOf("sessionId" to session.id))
    }
    suspend fun saveCheckpoint(point: Checkpoint) {
        val who = current(); check(who.uid == point.uid && !who.demo && !point.demo && who.role == "STUDENT")
        dao.insert(StoredCheckpoint("${point.uid}:${point.sessionId}:${point.slot}", point.uid, point.sessionId, point.slot,
            json.encodeToString(point), false, "PENDING"))
    }
    suspend fun uploadPending(): Boolean {
        val who = account ?: return true
        for (item in dao.pending(who.uid)) {
            if (account?.uid != who.uid) return false
            try {
                call("submitCheckpoint", mapOf("checkpointJson" to item.json))
                dao.mark(item.key, "SYNCED")
            } catch (e: CloudException) {
                val permanent = e.status in setOf(400, 403, 404, 409, 422)
                dao.mark(item.key, if (permanent) "REVIEW_REQUIRED" else "PENDING", e.message.orEmpty())
                if (!permanent) return false
            } catch (_: Exception) { return false }
        }
        return true
    }
    suspend fun roster(classId: String): List<RosterMember> {
        val who = current(); check(who.role == "TEACHER")
        return json.decodeFromString(call("listRoster", mapOf("classId" to classId)))
    }
    suspend fun enroll(classId: String, email: String) {
        check(current().role == "TEACHER" && !current().demo) { "Enrollment changes require an institution teacher account." }
        call("enrollStudent", mapOf("classId" to classId, "email" to email.trim()))
    }
    suspend fun removeEnrollment(classId: String, uid: String) {
        check(current().role == "TEACHER" && !current().demo) { "Enrollment changes require Supabase." }
        call("removeEnrollment", mapOf("classId" to classId, "uid" to uid))
    }
    suspend fun attendance(session: ClassSession): List<AttendanceRecord> {
        check(!session.demo) { "An institution session is required." }
        return json.decodeFromString(call("listAttendance", mapOf("sessionId" to session.id)))
    }
    suspend fun evidence(session: ClassSession, uid: String): List<Checkpoint> {
        check(!session.demo) { "An institution session is required." }
        return json.decodeFromString(call("listCheckpoints", mapOf("sessionId" to session.id, "uid" to uid)))
    }
    suspend fun override(session: ClassSession, uid: String, outcome: String, reason: String) {
        check(current().role == "TEACHER" && !session.demo) { "Corrections require an institution teacher account and a cloud audit log." }
        call("overrideAttendance", mapOf("sessionId" to session.id, "uid" to uid, "outcome" to outcome, "reason" to reason.trim()))
    }
    suspend fun publishConfig(roomId: String, config: PresenceConfig) {
        val who = current(); check(who.role == "TEACHER")
        config.validate()
        val samples = dao.calibrations(who.uid, roomId)
        check(samples.count { it.inside } >= 3 && samples.count { !it.inside } >= 3) { "Collect at least three inside and three outside samples first." }
        val beacons = roomBeacons(roomId)
        call("publishConfig", mapOf("roomId" to roomId, "configJson" to json.encodeToString(config),
            "samplesJson" to json.encodeToString(samples.take(60).map {
                val raw = json.decodeFromString<ScanBatch>(it.json)
                val compact = PresenceEngine().compact(raw, config, beacons)
                mapOf("label" to it.label, "inside" to it.inside.toString(), "batch" to json.encodeToString(compact))
            })))
    }
    suspend fun roomBeacons(roomId: String): List<BeaconIdentity> = json.decodeFromString(call("roomBeacons", mapOf("roomId" to roomId)))
    suspend fun roomConfig(roomId: String): PresenceConfig {
        return json.decodeFromString(call("roomConfig", mapOf("roomId" to roomId)))
    }
}
