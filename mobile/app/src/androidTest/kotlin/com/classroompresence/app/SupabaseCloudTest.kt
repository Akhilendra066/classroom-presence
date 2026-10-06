package com.classroompresence.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.classroompresence.data.CloudException
import com.classroompresence.data.PresenceRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only when the trusted test script supplies disposable live accounts. */
@RunWith(AndroidJUnit4::class)
class SupabaseCloudTest {
    @Test fun realAuthRolesPrivatePasswordSetupAndPersistence() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("cloudTeacherEmail"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = PresenceRepository(context)
        repository.logout()
        assertTrue(repository.cloudConfigured)
        val password = args.getString("cloudPassword")!!
        repository.setPasswordFromLink(args.getString("cloudSetupLink")!!, password)
        try {
            repository.login(args.getString("cloudTeacherEmail")!!, password, "STUDENT")
            fail("Role selection must not promote or change access")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("not assigned")) }
        val teacher = repository.login(args.getString("cloudTeacherEmail")!!, password, "TEACHER")
        assertEquals("TEACHER", teacher.role)
        assertFalse(teacher.demo)
        val info = repository.classes().single { it.id == args.getString("cloudClassId") }
        assertEquals(1, repository.roster(info.id).size)
        // A new client instance decrypts the saved session, without logging in again.
        val reopened = PresenceRepository(context)
        assertEquals(teacher.uid, reopened.account!!.uid)
        assertEquals(1, reopened.roster(info.id).size)
        try { reopened.startSession(info); fail("An uncalibrated room must not start") }
        catch (e: CloudException) { assertEquals(400, e.status) }
        reopened.logout()
        val student = reopened.login(args.getString("cloudStudentEmail")!!, args.getString("cloudStudentPassword")!!, "STUDENT")
        assertEquals("STUDENT", student.role)
        assertTrue(reopened.classes().any { it.id == info.id })
        try { reopened.roster(info.id); fail("Student must not read roster") }
        catch (_: IllegalStateException) { }
        assertEquals(0, reopened.sessions().size)
        reopened.logout()
    }
}
