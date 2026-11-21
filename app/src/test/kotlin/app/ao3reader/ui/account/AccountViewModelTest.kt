package app.ao3reader.ui.account

import app.ao3reader.account.SessionSupersededException
import app.ao3reader.account.SignInResult
import app.ao3reader.ao3.net.FetchResult
import app.ao3reader.ao3.parse.AccountState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var source: FakeAccount

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        source = FakeAccount()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private class FakeAccount : AccountSource {
        override val state = MutableStateFlow(AccountState(false, null))
        override val needsSignIn = MutableStateFlow(false)
        override val credentialsSaved = MutableStateFlow(false)
        var signInCalls = 0
        var signOutCalls = 0
        var saveOnOk = true
        var next = CompletableDeferred<SignInResult>()

        override suspend fun signIn(username: String, password: String): SignInResult {
            signInCalls++
            val r = next.await()
            if (r == SignInResult.Ok) {
                state.value = AccountState(true, username)
                credentialsSaved.value = saveOnOk
            }
            return r
        }

        override suspend fun signOut() {
            signOutCalls++
            state.value = AccountState(false, null)
            credentialsSaved.value = false
        }
    }

    private suspend fun TestScope.signInWith(vm: AccountViewModel, result: SignInResult) {
        source.next = CompletableDeferred()
        vm.signIn("owner", "pw")
        runCurrent()
        source.next.complete(result)
        advanceUntilIdle()
    }

    @Test fun `sign-out cleanup runs every step even when earlier ones throw`() = test {
        val ran = mutableListOf<Int>()
        val e = runCatching {
            runEach(listOf(
                { ran += 1; throw IOException("a") },
                { ran += 2 },
                { ran += 3; throw IllegalStateException("c") },
                { ran += 4 },
            ))
        }.exceptionOrNull()
        assertEquals(listOf(1, 2, 3, 4), ran)
        assertTrue(e is IllegalStateException)
    }

    @Test fun `state mirrors the session`() = test {
        source.state.value = AccountState(true, "Owner")
        source.credentialsSaved.value = true
        val vm = AccountViewModel(source)
        assertTrue(vm.state.value.signedIn)
        assertEquals("Owner", vm.state.value.username)
        source.state.value = AccountState(false, null)
        advanceUntilIdle()
        assertFalse(vm.state.value.signedIn)
        assertNull(vm.state.value.username)
    }

    @Test fun `submit needs both fields and no request in flight`() = test {
        val vm = AccountViewModel(source)
        assertFalse(vm.canSubmit("", "pw"))
        assertFalse(vm.canSubmit("  ", "pw"))
        assertFalse(vm.canSubmit("owner", ""))
        assertTrue(vm.canSubmit("owner", "pw"))
        vm.signIn("owner", "")
        runCurrent()
        assertEquals(0, source.signInCalls)

        vm.signIn("owner", "pw")
        runCurrent()
        assertTrue(vm.state.value.busy)
        assertFalse(vm.canSubmit("owner", "pw"))
        vm.signIn("owner", "pw")
        runCurrent()
        assertEquals(1, source.signInCalls)
        source.next.complete(SignInResult.Rejected("no"))
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertTrue(vm.canSubmit("owner", "pw"))
    }

    @Test fun `Ok with saved credentials closes the screen`() = test {
        val vm = AccountViewModel(source)
        signInWith(vm, SignInResult.Ok)
        val s = vm.state.value
        assertTrue(s.close)
        assertTrue(s.signedIn)
        assertEquals("owner", s.username)
        assertFalse(s.busy)
        assertNull(s.error)
        vm.closeHandled()
        advanceUntilIdle()
        assertFalse(vm.state.value.close)
    }

    @Test fun `Ok without saved credentials stays and warns`() = test {
        source.saveOnOk = false
        val vm = AccountViewModel(source)
        signInWith(vm, SignInResult.Ok)
        val s = vm.state.value
        assertFalse(s.close)
        assertTrue(s.signedIn)
        assertFalse(s.credentialsSaved)
    }

    @Test fun `Rejected shows AO3's message or a default`() = test {
        val vm = AccountViewModel(source)
        signInWith(vm, SignInResult.Rejected("Invalid login."))
        assertEquals("Invalid login.", vm.state.value.error)
        assertFalse(vm.state.value.close)
        signInWith(vm, SignInResult.Rejected("  "))
        assertEquals("AO3 didn't accept those details.", vm.state.value.error)
    }

    @Test fun `errors map to copy`() = test {
        val vm = AccountViewModel(source)
        signInWith(vm, SignInResult.Error(FetchResult.Blocked))
        assertEquals("AO3 is blocking requests right now.", vm.state.value.error)
        signInWith(vm, SignInResult.Error(FetchResult.RateLimited(30)))
        assertEquals("AO3 asked us to slow down. Try again in 30 s.", vm.state.value.error)
        signInWith(vm, SignInResult.Error(FetchResult.Failed(IOException("x"))))
        assertEquals("Couldn't sign in. Check your connection.", vm.state.value.error)
        signInWith(vm, SignInResult.Error(FetchResult.NotFound))
        assertEquals("Couldn't sign in. Check your connection.", vm.state.value.error)
    }

    @Test fun `a superseded sign-in is silent`() = test {
        val vm = AccountViewModel(source)
        signInWith(vm, SignInResult.Error(FetchResult.Blocked))
        signInWith(vm, SignInResult.Error(FetchResult.Failed(SessionSupersededException())))
        val s = vm.state.value
        assertNull(s.error)
        assertFalse(s.busy)
        assertFalse(s.close)
    }

    @Test fun `a new submit clears the previous error`() = test {
        val vm = AccountViewModel(source)
        signInWith(vm, SignInResult.Rejected("no"))
        source.next = CompletableDeferred()
        vm.signIn("owner", "pw2")
        runCurrent()
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.busy)
    }

    @Test fun `sign out needs a second tap within 3 s`() = test {
        source.state.value = AccountState(true, "Owner")
        val vm = AccountViewModel(source)
        vm.signOutTap()
        runCurrent()
        assertTrue(vm.state.value.confirmSignOut)
        assertEquals(0, source.signOutCalls)
        advanceTimeBy(3_001)
        assertFalse(vm.state.value.confirmSignOut)

        vm.signOutTap()
        advanceTimeBy(2_000)
        vm.signOutTap()
        advanceUntilIdle()
        assertEquals(1, source.signOutCalls)
        assertFalse(vm.state.value.signedIn)
        assertFalse(vm.state.value.confirmSignOut)
    }

    @Test fun `sign out resets the state`() = test {
        val vm = AccountViewModel(source)
        signInWith(vm, SignInResult.Error(FetchResult.Blocked))
        source.saveOnOk = true
        signInWith(vm, SignInResult.Ok)
        vm.closeHandled()
        vm.signOutTap(); vm.signOutTap()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(AccountUiState(signedIn = false, username = null, credentialsSaved = false), s)
    }

    @Test fun `busy while signing out`() = test {
        source.state.value = AccountState(true, "Owner")
        val gate = CompletableDeferred<Unit>()
        val src = object : AccountSource by source {
            override suspend fun signOut() { gate.await(); source.signOut() }
        }
        val vm = AccountViewModel(src)
        vm.signOutTap(); vm.signOutTap()
        runCurrent()
        assertTrue(vm.state.value.busy)
        vm.signOutTap()
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertEquals(1, source.signOutCalls)
    }

    @Test fun `no signed-in view or unsaved note until the sign-in call returns`() = test {
        // Session commits the signed-in state before it saves the credentials.
        val saved = CompletableDeferred<Unit>()
        val src = object : AccountSource by source {
            override suspend fun signIn(username: String, password: String): SignInResult {
                source.state.value = AccountState(true, username)
                saved.await()
                source.credentialsSaved.value = true
                return SignInResult.Ok
            }
        }
        val vm = AccountViewModel(src)
        vm.signIn("owner", "pw")
        advanceUntilIdle()
        val mid = vm.state.value
        assertTrue(mid.busy)
        assertFalse(mid.signedIn)
        assertNull(mid.username)
        assertFalse(mid.close)
        saved.complete(Unit)
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.signedIn)
        assertTrue(s.credentialsSaved)
        assertTrue(s.close)
        assertFalse(s.busy)
    }

    @Test fun `a throwing sign-in shows the connection copy`() = test {
        val src = object : AccountSource by source {
            override suspend fun signIn(username: String, password: String): SignInResult = throw IOException("boom")
        }
        val vm = AccountViewModel(src)
        vm.signIn("owner", "pw")
        advanceUntilIdle()
        assertEquals("Couldn't sign in. Check your connection.", vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `sign out re-arms after the confirmation lapses`() = test {
        source.state.value = AccountState(true, "Owner")
        val vm = AccountViewModel(source)
        vm.signOutTap()
        advanceTimeBy(3_001)
        runCurrent()
        assertFalse(vm.state.value.confirmSignOut)
        vm.signOutTap()
        runCurrent()
        assertTrue(vm.state.value.confirmSignOut)
        assertEquals(0, source.signOutCalls)
        advanceTimeBy(3_001)
        runCurrent()
        assertFalse(vm.state.value.confirmSignOut)
        assertEquals(0, source.signOutCalls)
    }
}
