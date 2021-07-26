package net.cellar;

import android.content.Context;
import android.util.JsonReader;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import net.cellar.worker.JsonShredderLoader;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Tests that specific Strings can be extracted from json data.
 * Succeeded 2021-07-14
 */
public class JsonShredderLoaderTest {

    private static final String JSON1 = "{\n" +
            "  \"versionCode\": 12345,\n" +
            "  \"versionName\": \"1.23.4\",\n" +
            "  \"sha256sum\": \"0000000000000000000000000000000000000000000000000000000000000000\",\n" +
            "  \"url\": \"https://updates.signal.orc/android/Signal.apk\"\n" +
            "}";

    private static final String KEY1 = "url";

    private Context ctx;

    @Before
    @CallSuper
    public void init() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void run() {
        App app = (App)ctx.getApplicationContext();

        JsonShredderLoader jsl;
        jsl = new JsonShredderLoader(1000, app.getOkHttpClient(), null) {
            @NonNull
            @Override
            public String getWantedKey() {
                return KEY1;
            }
        };
        StringBuilder sb = new StringBuilder();
        JsonReader reader = new JsonReader(new StringReader(JSON1));
        try {
            jsl.parseObject(reader, null, sb, 0);
        } catch (IOException e) {
            fail(e.toString());
        }
        assertEquals("https://updates.signal.orc/android/Signal.apk", sb.toString());
    }

}
