package nl.family7.tv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * De koude start tot het eerste scherm na de splash (zonder sessie is dat het
 * aanmeldscherm). Het pakket komt uit de build, zodat ook de testeditie werkt.
 */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() {
        val target = InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: "nl.family7.tv"
        rule.collect(packageName = target, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            // De splash vervaagt pas als het eerste scherm klaar is.
            device.wait(Until.hasObject(By.pkg(target).depth(0)), 5_000)
            device.waitForIdle()
            Thread.sleep(2_500)
            device.waitForIdle()
        }
    }
}
