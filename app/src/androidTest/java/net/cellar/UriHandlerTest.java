package net.cellar;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import net.cellar.supp.UriHandler;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@SmallTest
public class UriHandlerTest {

    private static void testPattern(@NonNull Pattern p, @NonNull String m, boolean expectingSuccess) {
        Matcher matcher = p.matcher(m);
        boolean matches = matcher.matches();
        if (expectingSuccess) {
            assertTrue("\"" + m + "\" not a match for \"" + p + "\"", matches);
        } else {
            assertFalse("\"" + m + "\" is a match for \"" + p + "\"", matches);
        }
    }

    @Test
    public void testUriHandlerPatterns() {
        testPattern(UriHandler.PATTERN_YOUTUBE, "http://www.youtube.com/v/XAB1CabDcdEe?hl=bb_FF&amp;version=3&amp;rel=0&amp;autoplay=1", true);
        testPattern(UriHandler.PATTERN_YOUTUBE, "https://www.youtube.com/embed/AB1CabDcdEe?hl=aa_EE&version=3&rel=0&autoplay=1", true);
        testPattern(UriHandler.PATTERN_YOUTUBE, "http://www.youtube.com/embed/AB1CabDcdEe?hl=aa_EE&version=3&rel=0&autoplay=1", true);
        testPattern(UriHandler.PATTERN_YOUTUBE, "https://www.youtube.com/watch?v=AB1CabDcdEe", true);
        testPattern(UriHandler.PATTERN_YOUTUBE, "http://www.youtube.com/embed//", false);
        testPattern(UriHandler.PATTERN_YOUTUBE, "https://www.youtube.com/watch?v=", false);
        testPattern(UriHandler.PATTERN_YOUTUBE, "https://www.youtube.com", false);
        testPattern(UriHandler.PATTERN_YOUTUBE, "https://www.youtube.com/", false);
        testPattern(UriHandler.PATTERN_YOUTUBE, "https://www.youtube.com//", false);
        testPattern(UriHandler.PATTERN_YOUTUBE, "https://www.meandyoutube.com/watch?v=AB1CabDcdEe", false);
        testPattern(UriHandler.PATTERN_YOUTUBE, "https://.youtube.com/watch?v=AB1CabDcdEe", false);
        testPattern(UriHandler.PATTERN_YOUTUBE, "content://www.youtube.com/watch?v=AB1CabDcdEe", false);

        testPattern(UriHandler.PATTERN_YOUTUBE_REDIR, "https://www.youtube.com/redirect?event=playlist_description&redir_token=QUFFLUhqbmxuV1BHbnhJLWpXcldBMW9Wd19uXzZiZUxjUXxBQ3Jtc0tsem5ZOVI0MFo5aWFDNGpOcEVkbzg0MGNKSFF3V2NDeXFrTV9vUXVMbzEtckpuSjZ2WXlyNVMxLThCN0hVUFhueldMTUpTSE1nWnJ5WjdGVmN4eGdjSWJIcnczeFl4NmhBVjl0YTlWeTMybi1PY1BVSQ&q=https%3A%2F%2Fwww.heise.de%2Fct%2Fuplink%2Fctuplink.rss", true);

        testPattern(UriHandler.PATTERN_YOUTU_BE, "http://youtu.be/AB1CabDcdEe", true);
        testPattern(UriHandler.PATTERN_YOUTU_BE, "https://youtu.be/AB1CabDcdEe", true);
        testPattern(UriHandler.PATTERN_YOUTU_BE, "https://youtu.be/9c-wOGOr0io", true);
        testPattern(UriHandler.PATTERN_YOUTU_BE, "https://youtu.be//aasd", false);
        testPattern(UriHandler.PATTERN_YOUTU_BE, "https://youtu.be//", false);
        testPattern(UriHandler.PATTERN_YOUTU_BE, "https://youtu.be/", false);
        testPattern(UriHandler.PATTERN_YOUTU_BE, "https://youtu.be", false);

        testPattern(UriHandler.PATTERN_GFYCAT, "https://gfycat.com/harmfulidioticuspresident", true);
        testPattern(UriHandler.PATTERN_GFYCAT, "http://gfycat.com/harmfulidioticuspresident", true);
        testPattern(UriHandler.PATTERN_GFYCAT, "https://gfycat.com/", false);
        testPattern(UriHandler.PATTERN_GFYCAT, "https://gfycat.com", false);

        testPattern(UriHandler.PATTERN_IMGUR, "https://imgur.com/gallery/XDbj49Q", true);
        testPattern(UriHandler.PATTERN_IMGUR, "https://i.imgur.com/gallery/XDbj49Q", true);
        testPattern(UriHandler.PATTERN_IMGUR, "https://imgur.com/4kYsf7E.gifv", true);
        testPattern(UriHandler.PATTERN_IMGUR, "https://i.imgur.com/4kYsf7E.gifv", true);
        testPattern(UriHandler.PATTERN_IMGUR, "https://imgur.com/", false);
        testPattern(UriHandler.PATTERN_IMGUR, "https://imgur.com/abracadabra/", false);

        testPattern(UriHandler.PATTERN_OPERAVISION, "https://operavision.eu/en/library/performances/operas/les-contes-dhoffmann-komische-oper-berlin", true);
        testPattern(UriHandler.PATTERN_OPERAVISION, "https://operavision.eu/fr/bibliotheque/spectacles/operas/les-contes-dhoffmann-komische-oper-berlin", true);
        testPattern(UriHandler.PATTERN_OPERAVISION, "https://operavision.eu/de/bibliothek/auffuehrungen/opern/les-contes-dhoffmann-komische-oper-berlin", true);
        testPattern(UriHandler.PATTERN_OPERAVISION, "https://operavision.eu/es/biblioteca/novela/opera/gonzales", false);

        testPattern(UriHandler.PATTERN_GUARDIAN, "https://www.theguardian.com/category/video/year/mon/dd/some-video-title-is-given-here", true);
        testPattern(UriHandler.PATTERN_GUARDIAN, "https://www.theguardian.com/", false);
        testPattern(UriHandler.PATTERN_GUARDIAN, "https://www.theguardian.com/politics/1940/mar/04/revealed-qe2-is-gay", false);

        testPattern(UriHandler.PATTERN_POSTIMG, "https://postimg.cc/N5kyc21w", true);
        testPattern(UriHandler.PATTERN_POSTIMG, "https://postimg.cc/gallery/r0VKmc8", false);

        testPattern(UriHandler.PATTERN_LOCGOV, "https://www.loc.gov/item/00694289/", true);
        testPattern(UriHandler.PATTERN_LOCGOV, "https://www.loc.gov/item/00694289", true);
        testPattern(UriHandler.PATTERN_LOCGOV, "https://www.loc.gov/item/", false);
        testPattern(UriHandler.PATTERN_LOCGOV, "https://www.loc.gov/", false);

        testPattern(UriHandler.PATTERN_NZZ, "https://www.nzz.ch/video/nzz-format/titel.1234567", true);
        testPattern(UriHandler.PATTERN_NZZ, "https://www.nzz.ch/video/", false);
        testPattern(UriHandler.PATTERN_NZZ, "https://www.nzz.ch/", false);

        testPattern(UriHandler.PATTERN_CN, "https://www.wired.com/video/watch/wired25-2020-watch-me", true);
        testPattern(UriHandler.PATTERN_CN, "https://www.wired.com/video/popular", false);
        testPattern(UriHandler.PATTERN_CN, "https://www.wired.com/", false);

        testPattern(UriHandler.PATTERN_ARSE, "https://arstechnica.com/video/watch/the-remarkable-paper-tablet-an-e-reader-you-can-write-on", true);
        testPattern(UriHandler.PATTERN_ARSE, "https://arstechnica.com/video/watch/", false);

        testPattern(UriHandler.PATTERN_BA, "https://www.filmothek.bundesarchiv.de/video/123456", true);
        testPattern(UriHandler.PATTERN_BA, "https://www.filmothek.bundesarchiv.de/video/123456?deedledoom", true);
        testPattern(UriHandler.PATTERN_BA, "https://www.filmothek.bundesarchiv.de/video/", false);
        testPattern(UriHandler.PATTERN_BA, "https://www.filmothek.bundesarchiv.de/", false);

        testPattern(UriHandler.PATTERN_RED, "https://v.redd.it/39f1kp3grhb61/DASH_720.mp4?source=fallback", true);
        testPattern(UriHandler.PATTERN_RED, "https://v.redd.it/fvcvyhic0ok61", true);
        testPattern(UriHandler.PATTERN_RED, "https://i.redd.it/fvcvyhic0ok61", false);
        testPattern(UriHandler.PATTERN_RED, "https://v.redd.it/", false);

        testPattern(UriHandler.PATTERN_ARD, "https://www.ardmediathek.de/rbb/video/abendschau/lockdown-mit-lockerungen/rbb-fernsehen/Y3JpZDovL3JiYi1vbmxpbmUuZGUvYWJlbmRzY2hhdS8yMDIxLTAzLTA0VDE5OjMwOjAwXzVkY2VjZDUwLTIzMmQtNGY0OS1hMDUxLWNkNmFjZWNkZTFjMy9sb2NrZG93bi1taXQtbG9ja2VydW5nZW4/", true);
        testPattern(UriHandler.PATTERN_ARD, "https://www.ardmediathek.de/ard/video/rockpalast/dire-straits/wdr-fernsehen/Y3JpZDovL3dkci5kZS9CZWl0cmFnLTY2ZmUzNDFhLWNhMGUtNGMzNy1hNDc0LTI0MzBjMzJmYTZhZA/", true);
        testPattern(UriHandler.PATTERN_ARD, "https://www.ardmediathek.de/video/rockpalast/bap-koelnarena-koeln-2006/wdr-fernsehen/Y3JpZDovL3dkci5kZS9CZWl0cmFnLThmNzBjYWZkLTA3Y2QtNGIyNC05ZjBkLTNhZGQwZGQ0Yjc5Yw/", true);

        testPattern(UriHandler.PATTERN_FRANCE24, "https://www.france24.com/en/tv-shows/down-to-earth/20210104-river-transport-reborn", true);
        testPattern(UriHandler.PATTERN_FRANCE24, "https://www.france24.com/en/tv-shows/access-asia/20201208-why-bear-attacks-are-on-the-rise-in-japan", true);
        testPattern(UriHandler.PATTERN_FRANCE24, "https://www.france24.com/", false);

        testPattern(UriHandler.PATTERN_POX, "https://www.dropbox.com/s/ptz4qc9r7zzblxp/4692.jpg?dl=0", true);
        testPattern(UriHandler.PATTERN_POX, "https://dl.dropbox.com/s/ptz4qc9r7zzblxp/4692.jpg?dl=2", false);

        testPattern(UriHandler.PATTERN_ZVIDEOX, "https://zvideox.net/watch/vC8NZH3ZRb0/how-to-use-kde-connect-to-connect-your-phone-to-your-pc/", true);
        testPattern(UriHandler.PATTERN_ZVIDEOX, "https://zvideox.net/witch/vC8NZH3ZRb0/how-to-use-kde-connect-to-connect-your-phone-to-your-pc/", false);
        testPattern(UriHandler.PATTERN_ZVIDEOX, "https://zvideox.net/", false);
    }

}
