package net.cellar;

import android.app.ApplicationErrorReport;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.TextUtils;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import net.cellar.supp.Util;
import net.cellar.worker.Loader;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests static methods from {@link Util}.<br>
 * Succeeded 2021-07-14
 */
@SmallTest
public class UtilTest {

    private Context ctx;

    @Before
    public void init() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void testAddExtensionFromMimeType() {
        File file = new File(ctx.getCacheDir(), "file");
        try {
            assertTrue(file.createNewFile());
            file = Util.addExtensionFromMimeType(file, "image/jpeg");
            assertTrue(file.getName().endsWith(".jpg"));
        } catch (Exception e) {
            fail(e.toString());
        } finally {
            Util.deleteFile(file);
        }
    }

    @Test
    public void testAsHex() {
        byte[] b = "ABC".getBytes();
        CharSequence cs = Util.asHex(b);
        assertNotNull(cs);
        assertEquals(b.length << 1, cs.length());
        assertEquals("414243", cs.toString());
    }

    /**
     * Tests the {@link Util#copy(InputStream, OutputStream, int) copy()} method.
     */
    @Test
    public void testCopy() {
        File src = null, dst = null;
        OutputStream out = null;
        try {
            src = File.createTempFile("src", null);
            dst = File.createTempFile("dst", null);
            final int n = 1024;
            out = new FileOutputStream(src);
            out.write(new byte[n]);
            Util.close(out);
            out = null;
            Assert.assertEquals(n, src.length());
            Util.copy(new FileInputStream(src), new FileOutputStream(dst), n >> 1);
            Assert.assertEquals(n, dst.length());
        } catch (Exception e) {
            fail(e.toString());
        } finally {
            Util.close(out);
            Util.deleteFile(dst, src);
        }
    }

    @Test
    public void testDeleteFileAndDirectory() {
        File dir = new File(ctx.getCacheDir(), "testdir");
        assertFalse(dir.isDirectory());
        assertTrue(dir.mkdirs());
        assertTrue(dir.isDirectory());
        File tmp = null;
        try {
            tmp = File.createTempFile("tmp", null, dir);
            assertTrue(tmp.isFile());
        } catch (IOException e) {
            fail(e.toString());
        } finally {
            Util.deleteFile(tmp);
        }
        Util.deleteDirectory(dir);
        assertFalse(tmp.isFile());
        assertFalse(dir.isDirectory());
    }

    @Test
    public void testGetExtensionUcase() {
        File file = new File("readme.txt");
        assertEquals("TXT", Util.getExtensionUcase(file));
    }

    @Test
    public void testGetFilePath() {
        File downloadsFolder = Util.getFilePath(ctx, App.FilePath.DOWNLOADS, false);
        assertNotNull(downloadsFolder);
        assertTrue(downloadsFolder.isDirectory());
    }

    @Test
    public void testGetHash() {
        String hash = Util.getHash("Hello World!", "SHA-1");
        assertNotNull(hash);
    }

    @Test
    public void testGetHostAndPort() {
        ConnectException e = new ConnectException("Failed to connect to example.com/127.0.0.1:1234");
        String hp = Util.getHostAndPort(e, null);
        assertEquals("example.com", hp);
        e = new ConnectException("Failed to connect to /127.0.0.1:1234");
        hp = Util.getHostAndPort(e, null);
        assertEquals("/127.0.0.1:1234", hp);
    }

    @Test
    public void testGetImageSize() {
        Bitmap b = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        OutputStream out = null;
        File tmp = null;
        try {
            tmp = File.createTempFile("tmp", "png");
            out = new FileOutputStream(tmp);
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            Util.close(out);
            out = null;
            int[] i = Util.getImageSize(tmp);
            assertNotNull(i);
            assertEquals(2, i.length);
            assertEquals(64, i[0]);
            assertEquals(32, i[1]);
        } catch (Exception e) {
            fail(e.toString());
        } finally {
            Util.close(out);
            Util.deleteFile(tmp);
        }
    }

    @Test
    public void testGetMime() {
        File dir = new File(ctx.getCacheDir(), "testdir");
        assertFalse(dir.isDirectory());
        assertTrue(dir.mkdirs());
        assertTrue(dir.isDirectory());
        assertEquals(android.provider.DocumentsContract.Document.MIME_TYPE_DIR, Util.getMime(dir));
        Util.deleteDirectory(dir);
        File tmp = null;
        try {
            tmp = File.createTempFile("tmp", ".jpg");
            assertTrue(tmp.isFile());
            assertEquals("image/jpeg", Util.getMime(tmp));
        } catch (IOException e) {
            fail(e.toString());
        } finally {
            Util.deleteFile(tmp);
        }
    }

    @Test
    public void testIsAudio() {
        assertTrue(Util.isAudio("music.mp3"));
        assertFalse(Util.isAudio("music.mp4"));
    }

    @Test
    public void testIsMovie() {
        assertTrue(Util.isMovie("movie.mp4"));
        assertFalse(Util.isMovie("movie.mp3"));
    }

    @Test
    public void testMakeCharBitmap() {
        Bitmap b = Util.makeCharBitmap("ABC", 0f, 32, 32, Color.BLACK, Color.WHITE, null);
        assertNotNull(b);
        assertEquals(32, b.getWidth());
        assertEquals(32, b.getHeight());
        int p00 = b.getPixel(0,0);
        assertTrue(p00 == Color.WHITE || p00 == Color.BLACK);
    }

    @Test
    public void testMakeErrorReport() {
        Exception e = new Exception("TEST");
        ApplicationErrorReport aer = Util.makeErrorReport(ctx, e);
        assertNotNull(aer);
    }

    @Test
    public void testParseDate() {
        Date d = Util.parseDate("Sat, 29 Oct 1994 19:43:31 GMT", Loader.DF, null);
        assertNotNull(d);
        GregorianCalendar calendar = new GregorianCalendar();
        calendar.setTime(d);
        assertEquals(29, calendar.get(Calendar.DAY_OF_MONTH));
        assertEquals(1994, calendar.get(Calendar.YEAR));
    }

    @Test
    public void testParseDouble() {
        double d = Util.parseDouble("123", 321.);
        assertEquals(123., d, 0.01);
    }

    @Test
    public void testParseInt() {
        int i = Util.parseInt("123", 321);
        assertEquals(123, i);
    }

    @Test
    public void testParseLong() {
        long l = Util.parseLong("123", 321L);
        assertEquals(123L, l);
    }

    @Test
    public void testSanitizeFilename() {
        String illegal = "<file*/.txt";
        String sane = Util.sanitizeFilename(illegal).toString();
        assertEquals("-file--.txt", sane);
        String legal = "file.txt";
        assertEquals(legal, Util.sanitizeFilename(legal));
        String empty = "";
        assertEquals(empty, Util.sanitizeFilename(empty));
    }

    @Test
    public void testSuggestAlternativeFilename() {
        File tmp = null;
        try {
            tmp = File.createTempFile("tmp", ".jpg");
            assertTrue(tmp.isFile());
            String alt = Util.suggestAlternativeFilename(tmp);
            assertNotNull(alt);
            assertTrue(alt.contains(".1."));
            assertTrue(alt.startsWith("tmp"));
            assertTrue(alt.endsWith(".jpg"));
        } catch (IOException e) {
            fail(e.toString());
        } finally {
            Util.deleteFile(tmp);
        }
    }

    @Test
    public void testTrim() {
        CharSequence cs1 = "01234567890";
        Assert.assertTrue(TextUtils.equals("123456789", Util.trim(cs1, '0')));
        CharSequence cs2 = "";
        Assert.assertTrue(TextUtils.equals("", Util.trim(cs2, '0')));
        CharSequence cs3 = "0";
        Assert.assertTrue(TextUtils.equals("", Util.trim(cs3, '0')));
        CharSequence cs4 = "0000000000";
        Assert.assertTrue(TextUtils.equals("", Util.trim(cs4, '0')));
    }
}
