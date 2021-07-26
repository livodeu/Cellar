package net.cellar;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests whether<ul>
 * <li>com.google.android.documentsui</li>
 * <li>com.google.android.dialer</li>
 * </ul>
 * are visible to the app.<br>
 * Succeeded 2021-07-01 (API 28, 30)
 */
@SmallTest
public class InstalledAppsTest {

    private Context ctx;

    @Before
    public void init() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /**
     * This test will fail (on an API 30 emulator device)
     * if there isn't a &lt;queries&gt; section in the manifest.<br>
     * Can, of course, succeed only if the mentioned apps are installed in the first place!
     */
    @Test
    @FlakyTest
    public void getInstalledApps() {
        PackageManager pm = ctx.getPackageManager();
        assertNotNull(pm);
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        assertNotNull(apps);
        assertTrue(apps.size() > 0);
        //java.util.Collections.sort(apps, (o1, o2) -> o1.packageName.compareTo(o2.packageName));
        boolean documentsuiFound = false;
        boolean dialerFound = false;
        int missing = 2;
        for (ApplicationInfo ai : apps) {
            if ("com.google.android.documentsui".equals(ai.packageName) || "com.android.documentsui".equals(ai.packageName)) {documentsuiFound = true; missing--;}
            if ("com.google.android.dialer".equals(ai.packageName) || "com.android.dialer".equals(ai.packageName)) {dialerFound = true; missing--;}
        }
        StringBuilder err = new StringBuilder();
        if (!documentsuiFound) err.append("com.*.android.documentsui not found!");
        if (!dialerFound) err.append(" com.*.android.dialer not found!");
        assertEquals(err.toString(),0, missing);
    }

}
