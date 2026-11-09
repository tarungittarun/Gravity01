package `in`.opentrack.india.ui

import `in`.opentrack.india.core.BuildStamp
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BuildIdentityTest {
    @Test fun diagnosticStampAgreesWithInstallableVersionName() {
        val app = RuntimeEnvironment.getApplication()
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        assertTrue("diagnostic stamp ${BuildStamp.STAMP} disagrees with APK $version",
            version.endsWith(BuildStamp.ROUND.lowercase()))
    }
}
