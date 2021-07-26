package net.cellar;

import androidx.annotation.NonNull;

import org.junit.Test;

public class SignalUpdateTest extends LoadTest {

    private static final String URL = "https://updates.signal.org/android/latest.json";

    @NonNull
    @Override
    String getUrl() {
        return URL;
    }

    @Test
    @Override
    public void execute() {
        super.load();
    }

}
