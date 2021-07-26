package net.cellar;

import android.net.Uri;

import androidx.test.filters.SmallTest;

import net.cellar.model.Credential;
import net.cellar.supp.UriUtil;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests static methods from {@link UriUtil}.<br>
 * Succeeded 2021-07-02
 */
@SmallTest
public class UriUtilTest {

    @Test
    public void testExtractUrl() {
        String s = "<a href=\"https://www.example.com/path/resource\">Link</a><a href=\"ftp://123.456.78.90/path/res\">Another</a>";
        String u = UriUtil.extractUrl(s);
        assertNotNull(u);
        assertEquals("https://www.example.com/path/resource", u);
        assertNull(UriUtil.extractUrl(""));
    }

    @Test
    public void testFromUri() {
        Uri uri = Uri.parse("file://readme.txt");
        java.io.File file = UriUtil.fromUri(uri);
        assertNotNull(file);
        assertEquals("readme.txt", file.getName());
        uri = Uri.parse("http://example.com/path/resource");
        assertNull(UriUtil.fromUri(uri));
    }

    @Test
    public void testGetCredential() {
        Uri uri = Uri.parse("https://user:password@example.com/path/resource");
        Credential c = UriUtil.getCredential(uri);
        assertNotNull(c);
        assertEquals("user", c.getUserid());
        assertNotNull(c.getPassword());
        assertEquals("password", c.getPassword().toString());
    }

    @Test
    public void testIsRemoteUrl() {
        Uri uri1 = Uri.parse("http://example.com/path/resource");
        Uri uri2 = Uri.parse("content://net.cellar/path/resource");
        assertTrue(UriUtil.isRemoteUrl(uri1.toString()));
        assertFalse(UriUtil.isRemoteUrl(uri2.toString()));
    }

    @Test
    public void testIsSupportedLocalScheme() {
        assertTrue(UriUtil.isSupportedLocalScheme("content"));
        assertFalse(UriUtil.isSupportedLocalScheme("http"));
    }

    @Test
    public void testIsSupportedRemoteScheme() {
        assertFalse(UriUtil.isSupportedRemoteScheme("content"));
        assertTrue(UriUtil.isSupportedRemoteScheme("http"));
    }

    @Test
    public void testIsUrl() {
        assertTrue(UriUtil.isUrl("http://www.kugel.com"));
        assertTrue(UriUtil.isUrl("https://www.kugel.com"));
        assertFalse(UriUtil.isUrl("htp://www.kugel.com"));
        assertFalse(UriUtil.isUrl("rtsp://www.kugel.com"));
    }
}
