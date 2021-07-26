package net.cellar;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import net.cellar.supp.Util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the Dogs DogumentProvider.<br>
 * Was a good boy on 2021-07-01
 */
@SmallTest
public class DogsTest {

    private static final int N_TEST_FILES = 3;
    private static final String TEST_FILE_MIME = "text/plain";
    private static final String TEST_FILE_PREFIX = "test";
    private static final String TEST_FILE_SUFFIX = ".txt";
    private final List<File> testData = new ArrayList<>(N_TEST_FILES);
    private Context ctx;
    private Dogs dogs;

    private void createTestData() {
        File dir = App.getDownloadsDir(this.ctx);
        for (int i = 0; i < N_TEST_FILES; i++) {
            File t;
            do {
                t = new File(dir, TEST_FILE_PREFIX + Math.round(Math.random() * 1_000_000) + TEST_FILE_SUFFIX);
            } while (this.testData.contains(t));
            try {
                boolean created = t.createNewFile();
                assertTrue(created);
                this.testData.add(t);
            } catch (IOException e) {
                fail(e.toString());
            }
        }
    }

    @After
    public void exit() {
        for (File t : this.testData) Util.deleteFile(t);
    }

    @Before
    public void init() {
        this.ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        this.dogs = new Dogs();
        this.dogs.setDebugContext(this.ctx);
        createTestData();
        this.dogs.refresh();
    }

    @Test
    public void testDogsDeleteDocument() {
        File file = this.testData.get(0);
        assertTrue(file.isFile());
        assertTrue(file.setWritable(false, false));
        try {
            this.dogs.deleteDocument(file.getAbsolutePath());
            fail("deleteDocument() did not throw UnsupportedOperationException!");
        } catch (UnsupportedOperationException ignored) {
            // this is expected
        } catch (FileNotFoundException e) {
            fail(e.toString());
        }
        assertTrue(file.isFile());
        try {
            assertTrue(file.setWritable(true, false));
            this.dogs.deleteDocument(file.getAbsolutePath());
            assertFalse(file.isFile());
        } catch (FileNotFoundException e) {
            fail(e.toString());
        }
    }

    @Test
    public void testDogsGetDocumentType() {
        try {
            String mime = this.dogs.getDocumentType(this.testData.get(0).getAbsolutePath());
            assertEquals(TEST_FILE_MIME, mime);
        } catch (FileNotFoundException e) {
            fail(e.toString());
        }
    }

    @Test
    public void testDogsOpenDocument() {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = this.dogs.openDocument(this.testData.get(0).getAbsolutePath(), "r", null);
            assertNotNull(pfd);
        } catch (FileNotFoundException e) {
            fail(e.toString());
        } finally {
            Util.close(pfd);
        }
    }

    @Test
    public void testDogsQueryChildDocuments() {
        Cursor cursor = null;
        //noinspection TryFinallyCanBeTryWithResources
        try {
            cursor = this.dogs.queryChildDocuments(Dogs.ROOT_DOC,null, DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            assertNotNull(cursor);
            int n = cursor.getCount();
            assertTrue("queryChildDocuments() did not return at least " + N_TEST_FILES + " results but " + n, n >= N_TEST_FILES);
        } catch (Exception e) {
            fail(e.toString());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    @Test
    public void testDogsQueryDocument() {
        Cursor cursor = null;
        //noinspection TryFinallyCanBeTryWithResources
        try {
            cursor = this.dogs.queryDocument(this.testData.get(0).getAbsolutePath(), null);
            assertNotNull(cursor);
            int n = cursor.getCount();
            assertEquals("queryDocument() did not return 1 result but " + n, 1, n);
        } catch (Exception e) {
            fail(e.toString());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    @Test
    public void testDogsQueryRoots() {
        Cursor cursor = null;
        //noinspection TryFinallyCanBeTryWithResources
        try {
            cursor = this.dogs.queryRoots(null);
            assertNotNull(cursor);
            int n = cursor.getCount();
            assertEquals("queryRoots() did not return 1 result but " + n, 1, n);
        } catch (Exception e) {
            fail(e.toString());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    @Test
    public void testDogsRecents() {
        Cursor cursor = null;
        //noinspection TryFinallyCanBeTryWithResources
        try {
            cursor = this.dogs.queryRecentDocuments(Dogs.ROOT_ID, null);
            assertNotNull(cursor);
            int n = cursor.getCount();
            assertTrue("queryRecentDocuments() did not return at least " + N_TEST_FILES + " results but " + n, n >= N_TEST_FILES);
        } catch (FileNotFoundException e) {
            fail(e.toString());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    @Test
    public void testDogsRenameDocument() {
        File file = this.testData.get(0);
        assertTrue(file.isFile());
        String oldPath = file.getAbsolutePath();
        String oldName = file.getName();
        String newName = "Malaclemys";
        try {
            String newPath = this.dogs.renameDocument(oldPath, newName);
            assertNotNull(newPath);
            assertTrue("New path: " + newPath, newPath.endsWith(newName + TEST_FILE_SUFFIX));
            newPath = this.dogs.renameDocument(newPath, oldName);
            assertEquals("New path: " + newPath, oldPath, newPath);
        } catch (FileNotFoundException e) {
            fail(e.toString());
        }
    }

    @Test
    public void testDogsSearch() {
        Cursor cursor = null;
        //noinspection TryFinallyCanBeTryWithResources
        try {
            cursor = this.dogs.querySearchDocuments(Dogs.ROOT_ID, TEST_FILE_PREFIX, null);
            assertNotNull(cursor);
            int n = cursor.getCount();
            assertTrue("querySearchDocuments() did not return at least " + N_TEST_FILES + " results but " + n, n >= N_TEST_FILES);
        } catch (FileNotFoundException e) {
            fail(e.toString());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    @Test
    public void testMakeAssetFileDescriptor() {
        Drawable d = this.ctx.getResources().getDrawable(R.mipmap.ic_launcher_foreground);
        assertNotNull(d);
        assertTrue(d instanceof BitmapDrawable);
        BitmapDrawable bmd = (BitmapDrawable)d;
        Bitmap b = bmd.getBitmap();
        assertNotNull(b);
        AssetFileDescriptor afd = Dogs.makeAssetFileDescriptor(b);
        assertNotNull(afd);
        Util.close(afd);
    }

}
