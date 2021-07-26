package net.cellar;

import androidx.annotation.NonNull;
import androidx.test.filters.LargeTest;

/**
 * Succeeded 2021-06-29
 */
@LargeTest
public class France24Test extends LoadTest {

    @NonNull
    @Override
    String getUrl() {
        return "https://www.france24.com/en/tv-shows/the-new-normal/20201016-the-new-normal-france-24-reports-from-azerbaijan-to-kenya";
    }

    @Override
    public void execute() {
        super.load();
    }

}
