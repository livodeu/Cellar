#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

-keep class org.beyka.tiffbitmapfactory.** { *; }

-keep class wseemann.media.FFmpegMediaMetadataRetriever { *; }

-keep public class net.cellar.R$raw
-keepclassmembers class net.cellar.R$raw {
    public *;
}
-printusage ../doc/removedby4r8.txt