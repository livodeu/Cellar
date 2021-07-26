package net.cellar;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.supp.Util;
import net.cellar.worker.Downloader;
import net.cellar.worker.LoaderListener;

import org.junit.Before;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests a download via https.<br>
 * Succeeded 2021-07-02
 */
@SuppressWarnings("BusyWait")
@SmallTest
public class HttpsTest implements LoaderListener {

    private static final String URL = "https://archive.org/download/mma_cypresses_437980/437980.jpg";
    private final int downloadId = 1001;
    private Context ctx;
    private volatile boolean done;

    @Before
    public void init() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void testHttps() {
        done = false;
        App app = (App)ctx.getApplicationContext();
        Downloader loader = new Downloader(downloadId, app.getOkHttpClient(),this);
        Order order = new Order(Uri.parse(URL));
        order.setDestinationFolder(App.getDownloadsDir(app).getAbsolutePath());
        order.setDestinationFilename("cypresses.jpg");
        order.setMime("image/jpg");
        loader.execute(order);
        while (!done) {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                fail(e.toString());
            }
        }
    }

    @Override
    public void done(int id, boolean complete, @NonNull Set<Delivery> deliveries) {
        java.io.File file = null;
        try {
            assertEquals(downloadId, id);
            assertTrue(complete);
            assertNotNull(deliveries);
            assertEquals(1, deliveries.size());
            Delivery delivery = deliveries.iterator().next();
            assertNotNull(delivery);
            file = delivery.getFile();
            assertNotNull(file);
            assertTrue(file.isFile());
            assertTrue(file.length() > 0L);
            assertEquals(200, delivery.getRc());
        } finally {
            Util.deleteFile(file);
            done = true;
        }
    }
}
