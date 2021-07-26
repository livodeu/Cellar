package net.cellar;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.platform.app.InstrumentationRegistry;

import net.cellar.supp.Log;
import net.cellar.supp.Util;
import net.cellar.worker.Inspector;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests {@link Inspector}.<br
 * Succeeded 2021-07-02
 */
public class InspectorTest {

    private Context ctx;
    private Map<File, String> suggestions;

    @Before
    public void init() {
        this.ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        this.suggestions = new HashMap<>();
    }

    /**
     * Tests that the Inspector's suggestions match the extensions of the existing files.<br>
     * This, of course, depends on a) that files exist and b) they are correctly named.
     */
    @Test
    public void test() {
        File dir = App.getDownloadsDir(ctx);
        assertTrue(dir.isDirectory());
        final File[] files = dir.listFiles();
        assertNotNull(files);
        Assume.assumeTrue("There are no files in the download directory",files.length > 0);
        final List<String> fails = new ArrayList<>();

        final boolean toybox = Inspector.testToybox();

        for (File file : files) {
            int dot = file.getName().lastIndexOf('.');
            if (dot < 0) continue;
            String real = file.getName().substring(dot);
            String extension = toybox ? Inspector.inspectViaToybox(file, suggestions) : Inspector.inspectFile(file, suggestions);
            if (!real.equals(extension)) fails.add("Inspector suggests " + extension + " for file \"" + file.getName() + "\".");
        }
        StringBuilder msg = new StringBuilder();
        for (String fail : fails) {
            msg.append(fail);
            Log.w(getClass().getSimpleName(), fail);
        }
        assertTrue(msg.toString(), fails.isEmpty());
    }

    /**
     * Tests the capability of Inspector to ignore user-specified files.
     */
    @Test
    public void testIgnored() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        SharedPreferences.Editor ed;
        Set<String> existing = prefs.getStringSet(Inspector.PREF_INSPECTOR_IGNORED, null);
        Set<String> temp;
        final Inspector inspector = new Inspector(ctx, () -> {});

        // a) create a file where extension and content do not match, add it to the ignore list and expect to get no suggestion
        File tmpFile = null;
        OutputStream out = null;
        try {
            tmpFile = File.createTempFile("temp", ".txt", App.getDownloadsDir(ctx));
            out = new FileOutputStream(tmpFile);
            out.write("%PDF-xxxxxx".getBytes());   // that would be a .pdf file
            Util.close(out);
            out = null;
            temp = new HashSet<>();
            if (existing != null) temp.addAll(existing);
            temp.add(tmpFile.getName());
            ed = prefs.edit();
            ed.putStringSet(Inspector.PREF_INSPECTOR_IGNORED, temp);
            ed.commit();
            // allow Inspector to reload
            Thread.sleep(1000);
            //
            Map<File, String> suggestions = inspector.doInBackground(tmpFile);
            assertTrue("Did get suggestions: " + suggestions.values(), suggestions.isEmpty());
        } catch (Exception e) {
            fail(e.toString());
        } finally {
            Util.close(out);
            Util.deleteFile(tmpFile);
        }

        // b) make sure that entries for non-existing files are removed
        existing = prefs.getStringSet(Inspector.PREF_INSPECTOR_IGNORED, null);
        temp = new HashSet<>();
        if (existing != null) temp.addAll(existing);
        final String nonExistingFile = String.valueOf(System.currentTimeMillis());
        ed = prefs.edit();
        temp.add(nonExistingFile);
        ed.putStringSet(Inspector.PREF_INSPECTOR_IGNORED, temp);
        ed.commit();
        int removed = inspector.cleanIgnoreSet(ctx);
        assertTrue("Inspector did not remove entry for " + nonExistingFile,removed > 0);
        existing = prefs.getStringSet(Inspector.PREF_INSPECTOR_IGNORED, null);
        assertTrue(existing == null || !existing.contains(nonExistingFile));
    }
}
