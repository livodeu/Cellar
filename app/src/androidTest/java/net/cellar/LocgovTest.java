package net.cellar;

import androidx.annotation.NonNull;
import androidx.test.filters.LargeTest;

/**
 * Succeeded 2021-06-29
 */
@LargeTest
public class LocgovTest extends LoadTest {

    @NonNull
    @Override
    String getUrl() {
        return "https://www.loc.gov/item/00694289/";
    }

    @Override
    public void execute() {
        super.load();
    }

}
