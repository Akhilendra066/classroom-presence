package com.classroompresence.app

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.classroompresence.core.Account
import com.classroompresence.data.PresenceRepository
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstitutionAccessTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun signInRequiresInstitutionCredentialsAndOffersNoSyntheticWorkflow() {
        compose.onNodeWithText("Student sign-in").assertExists()
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
        compose.onAllNodesWithText("demo", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Simulator", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Teacher login").performScrollTo().performClick()
        compose.onNodeWithText("Teacher sign-in").assertExists()
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
        compose.onNodeWithText("Forgot password").assertExists()
        compose.onAllNodesWithText("demo", substring = true).assertCountEquals(0)
    }

    @Test fun updateRejectsLegacyLocalAccountsAndPreservesInstitutionAccounts() {
        val preferences = compose.activity.getSharedPreferences("account", Context.MODE_PRIVATE)
        val savedAccount = preferences.getString("account", null)
        val savedBackend = preferences.getString("backend", null)
        val repository = PresenceRepository(compose.activity)
        try {
            val legacy = Account("legacy-test", "Legacy account", "STUDENT", true)
            preferences.edit().putString("account", repository.json.encodeToString(legacy))
                .putString("backend", "supabase-v1").commit()
            assertNull(PresenceRepository(compose.activity).account)
            assertNull(preferences.getString("account", null))
            try {
                repository.remember(legacy)
                fail("Local synthetic accounts must not be accepted")
            } catch (_: IllegalStateException) { }
            // Persistence regression only; this does not represent an authenticated server user.
            val institution = Account("persistence-test", "Institution account", "STUDENT")
            repository.remember(institution)
            assertEquals(institution, PresenceRepository(compose.activity).account)
        } finally {
            preferences.edit().putString("account", savedAccount).putString("backend", savedBackend).commit()
        }
    }
}
