package com.dcode.circleid

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcode.circleid.verify.VStep
import com.dcode.circleid.verify.VerifyViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VerifyViewModelTest {
    @Test
    fun cancelEndsTheFlowAndTheFirstOutcomeWins() {
        val vm = VerifyViewModel(ApplicationProvider.getApplicationContext())
        vm.begin(null)
        assertEquals(VStep.Fingerprint, vm.step)
        vm.cancel()
        val step = vm.step
        assertTrue(step is VStep.Result && !step.success)
        val first = vm.outcome.value
        assertFalse(first!!.ok)
        vm.cancel()
        assertTrue(vm.outcome.value === first)
    }
}
