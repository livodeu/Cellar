package net.cellar;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.Uri;

import androidx.annotation.RequiresApi;
import androidx.preference.PreferenceManager;
import androidx.test.filters.SdkSuppress;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import net.cellar.model.Wish;
import net.cellar.queue.QueueManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests the {@link QueueManager}.<br>
 * Succeeded 2021-07-01
 */
@SmallTest
public class QueueTest {

    private static final String URL1 = "https://archive.org/download/Corvairi1960/Corvairi1960_512kb.mp4";
    private static final String URL2 = "https://archive.org/download/SanFrancisco1955CinemascopeFilm/SanFrancisco.gif";
    private Context ctx;
    private JobScheduler js;

    @Before
    public void init() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        js = (JobScheduler)ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        assertNotNull(js);
        QueueManager.getInstance().deleteFile();
    }

    @After
    public void exit() {
        QueueManager.getInstance().deleteFile();
        js.cancel(QueueManager.JOB_ID);
    }

    @Test
    @SdkSuppress(minSdkVersion = 24)
    public void testAddToQueue() {
        Wish wish = new Wish(Uri.parse(URL1));
        QueueManager qm = QueueManager.getInstance();
        assertNotNull(qm);
        qm.clearQueue();
        int added = qm.add(wish);
        assertEquals(1, added);
        JobInfo ji = js.getPendingJob(QueueManager.JOB_ID); // <- needs API 24
        assertNotNull(ji);
        assertTrue("Job does not require storage being not low!", ji.isRequireStorageNotLow());
        assertFalse("Job is periodic!", ji.isPeriodic());
        assertTrue("Job is not persisted!", ji.isPersisted());
        assertNotNull(ji.getRequiredNetwork());
        boolean allowMetered = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(App.PREF_ALLOW_METERED, App.PREF_ALLOW_METERED_DEFAULT);
        if (!allowMetered) assertTrue("Job doesn not require non-metered network, contrary to prefs!", ji.getRequiredNetwork().hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        boolean removed = qm.remove(wish);
        assertTrue(removed);
        wwait(1000L);
    }

    @Test
    @RequiresApi(21)
    public void testAddHeldToQueue() {
        final QueueManager qm = QueueManager.getInstance();
        assertNotNull(qm);
        qm.clearQueue();
        assertFalse("Queue must be empty before this test can run!", qm.hasQueuedStuff());
        Wish wish = new Wish(Uri.parse(URL1));
        wish.setHeld(true);
        assertTrue("Wish is not held!", wish.isHeld());
        int added = qm.add(wish);
        assertEquals(1, added);
        assertTrue("Wish has not been queued!", qm.hasQueuedStuff());
        assertFalse("Wish has been taken off the queue though it was held!", qm.nextPlease());
        wwait(1_000L);
    }

    @Test
    public void testAddMulti() {
        Wish wish1 = new Wish(Uri.parse(URL1));
        Wish wish2 = new Wish(Uri.parse(URL2));
        QueueManager qm = QueueManager.getInstance();
        assertNotNull(qm);
        qm.clearQueue();
        int added = qm.add(wish1, wish2);
        assertEquals(2, added);
        wwait(1_000L);
    }

    private static void wwait(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
