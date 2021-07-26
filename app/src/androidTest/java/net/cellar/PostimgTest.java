package net.cellar;

import androidx.annotation.NonNull;
import androidx.test.filters.LargeTest;

/**
 * Succeeded 2021-06-29
 */
@LargeTest
public class PostimgTest extends LoadTest {

    @NonNull
    @Override
    String getUrl() {
        return "https://postimg.cc/N5kyc21w";
    }

    @Override
    public void execute() {
        super.load();
    }

}
