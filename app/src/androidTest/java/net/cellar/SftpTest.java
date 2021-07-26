package net.cellar;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.supp.Util;
import net.cellar.worker.LoaderListener;
import net.cellar.worker.SftpLoader;

import org.junit.Before;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests a download via sftp.<br>
 * Dependencies: 'com.jcraft:jsch:*' and 'com.jcraft:jzlib:*'<br>
 * Succeeded 2021-07-01
 */
@SuppressWarnings("BusyWait")
@MediumTest
public class SftpTest implements LoaderListener {

    /** credentials: user id "demo", pwd "password" (source: <a href="https://www.sftp.net/public-online-sftp-servers">https://www.sftp.net/public-online-sftp-servers</a>) */
    private static final String URL = "sftp://demo:password@test.rebex.net/readme.txt";
    private final int downloadId = 1001;
    private Context ctx;
    private volatile boolean done;

    @Before
    public void init() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void testSftp() {
        done = false;
        App app = (App)ctx.getApplicationContext();
        SftpLoader loader = new SftpLoader(downloadId, app, this);
        Order order = new Order(Uri.parse(URL));
        order.setDestinationFolder(App.getDownloadsDir(app).getAbsolutePath());
        order.setDestinationFilename("readme.txt");
        order.setMime("text/plain");
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
