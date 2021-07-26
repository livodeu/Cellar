package net.cellar;

import androidx.annotation.NonNull;
import androidx.test.filters.LargeTest;

/**
 * Tests a YT download.<br>
 * Dependency: 'com.github.TeamNewPipe:NewPipeExtractor:*'<br>
 * Succeeded 2021-06-29
 */
@LargeTest
public class YtTest extends LoadTest {

    @NonNull
    @Override
    String getUrl() {
        return "https://www.youtube.com/watch?v=uUWqUp_HeNs";
    }

    @Override
    public void execute() {
        super.load();
    }

}
