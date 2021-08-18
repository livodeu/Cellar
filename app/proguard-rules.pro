# http://developer.android.com/guide/developing/tools/proguard.html

# this does not seem to hurt much as far as the apk size is concerned
-keep class org.videolan.libvlc.** { *; }

-keep class org.beyka.tiffbitmapfactory.** { *; }

-keep class wseemann.media.FFmpegMediaMetadataRetriever { *; }

-keep public class net.cellar.R$raw
-keepclassmembers class net.cellar.R$raw {
    public *;
}
