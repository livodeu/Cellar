/*
 * Loader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.AnyThread;
import androidx.annotation.FloatRange;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Size;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.preference.PreferenceManager;

import com.google.android.material.snackbar.Snackbar;

import net.cellar.App;
import net.cellar.BuildConfig;
import net.cellar.LoaderService;
import net.cellar.R;
import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.net.EvilBlocker;
import net.cellar.net.Mores;
import net.cellar.net.NetworkChangedReceiver;
import net.cellar.supp.CoordinatorLayoutHolder;
import net.cellar.supp.Log;
import net.cellar.supp.Util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Base class for implementations in this package.
 */
abstract public class Loader extends AsyncTask<Order, Loader.Progress, Set<Delivery>> {

    /**
     * HTTP Date format<br>
     * e.g.: If-Modified-Since: Sat, 29 Oct 1994 19:43:31 GMT<br>
     * See <a href="https://tools.ietf.org/html/rfc2616#section-14.25">here</a>
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @SuppressLint("SimpleDateFormat")
    public static final DateFormat DF = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
    private static final Set<Delivery> NO_DELIVERIES = new HashSet<>(0);

    static {
        DF.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
    }

    /**
     * Utility method: download a HTTP(S) resource and store it in the given file.<br>
     * If not successful and if the file did not exist beforehand, the file will be deleted.<br>
     * Doesn't do any authentication!
     * @param client OkHttpClient (required)
     * @param url source (required)
     * @param dest destination (required)
     * @return HTTP return code
     * @throws NullPointerException if client or destination are {@code null}
     * @throws IllegalArgumentException if url is not valid
     */
    public static int downloadToFile(@NonNull OkHttpClient client, @NonNull String url, @NonNull File dest) {
        final boolean destExisted = dest.isFile();
        final long originalLength = dest.length();
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("Accept-Encoding", "gzip")
                ;
        if (originalLength > 0L) {
            requestBuilder.addHeader("Range", "bytes=" + originalLength + "-");
        }
        Request request = requestBuilder.build();
        Response response;
        ResponseBody body = null;
        InputStream in = null;
        int rc;
        try {
            response = client.newCall(request).execute();
            body = response.body();
            rc = response.code();
            if (!response.isSuccessful() || body == null) {
                if (body != null) {body.close(); body = null;}
                return rc;
            }
            String contentEncoding = response.header("Content-Encoding");
            final boolean gzip = "gzip".equals(contentEncoding) || "x-gzip".equals(contentEncoding);
            in = body.byteStream();
            if (gzip && !(in instanceof GZIPInputStream)) {
                in = new GZIPInputStream(in);
            }
            Util.copy(in, new FileOutputStream(dest), 4096);
        } catch (Exception e) {
            rc = 500;
            if (!destExisted) Util.deleteFile(dest);
            if (BuildConfig.DEBUG) Log.e(Loader.class.getSimpleName(), e.toString());
        } finally {
            Util.close(body, in);
        }
        return rc;
    }

    /**
     * Attempts to extract a filename from a Content-Disposition response header.<br>
     * <a href="https://tools.ietf.org/html/rfc2616#section-19.5.1">https://tools.ietf.org/html/rfc2616#section-19.5.1</a>
     * @param contentDisposition Content-Disposition response header
     * @return filename extracted from the header
     */
    @Nullable
    protected static String parseContentDisposition(@Nullable final String contentDisposition) {
        if (contentDisposition == null) return null;
        int pfs = contentDisposition.toLowerCase(java.util.Locale.US).indexOf("filename=\"");
        if (pfs < 0) return null;
        int pfe = contentDisposition.indexOf("\"", pfs + 10);
        if (pfe > pfs) {
            //extract file name; revert possible URL encoding (aka percent encoding); replace slashes with underscores
            return Uri.decode(contentDisposition.substring(pfs + 10, pfe)).replace(File.separatorChar, '_').trim();
        }
        return null;
    }

    /**
     * Updates the blacklist contents for {@link EvilBlocker}.
     * @param ctx Context
     * @param evilBlocker EvilBlocker
     * @param callback EvilBlocker.LoadedCallback (optional)
     */
    public static void updateEvilBlocker(@Nullable final Context ctx, final EvilBlocker evilBlocker, @Nullable final EvilBlocker.LoadedCallback callback) {
        if (ctx == null) {
            if (callback != null) callback.loaded(false, 0);
            return;
        }
        if (evilBlocker == null) {
            if (callback != null) callback.loaded(false, 0);
            return;
        }
        if (NetworkChangedReceiver.getInstance().getState() != NetworkInfo.State.CONNECTED) {
            if (callback != null) callback.loaded(false, 0);
            return;
        }
        String blacklistUrl = PreferenceManager.getDefaultSharedPreferences(ctx).getString(App.PREF_BLACKLIST, App.PREF_BLACKLIST_DEFAULT);
        evilBlocker.setUrl(ctx, blacklistUrl, (ok, count) -> {
            if (ctx instanceof Activity) {
                ((Activity)ctx).runOnUiThread(() -> {
                    if (ctx instanceof CoordinatorLayoutHolder) {
                        CoordinatorLayout cl = ((CoordinatorLayoutHolder) ctx).getCoordinatorLayout();
                        if (ok) Snackbar.make(cl, ctx.getString(R.string.msg_blacklist_loaded, count), Snackbar.LENGTH_LONG).show();
                        else Snackbar.make(cl, R.string.msg_blacklist_load_failed, Snackbar.LENGTH_LONG).show();
                    } else {
                        if (ok) Toast.makeText(ctx, ctx.getString(R.string.msg_blacklist_loaded, count), Toast.LENGTH_LONG).show();
                        else Toast.makeText(ctx, R.string.msg_blacklist_load_failed, Toast.LENGTH_LONG).show();
                    }
                });
            }
            if (callback != null) callback.loaded(ok, count);
        });
    }

    /** download id */
    protected final int id;
    private final Speed speed = new Speed();
    @Nullable protected Reference<LoaderListener> refListener;
    /** if set to {@code true}, a stop has been requested via {@link #holdon()} */
    protected volatile boolean stopRequested;
    @Nullable @Size(min = 1) private Order[] orders;
    /** used when not using okhttp with {@link net.cellar.App.Hal Hal} as its {@link okhttp3.Dns Dns} implementation */
    @Nullable private Mores mores;
    private boolean deferred;

    /**
     * Constructor.
     * @param id download id
     */
    private Loader(int id) {
        super();
        this.id = id;
    }

    /**
     * Constructor.
     * @param id download id
     * @param loaderListener LoaderListener
     */
    protected Loader(int id, @Nullable LoaderListener loaderListener) {
        this(id);
        this.refListener = loaderListener != null ? new WeakReference<>(loaderListener) : null;
    }

    /**
     * Performs cleanup actions after the work has been done.
     * And don't call super.
     */
    protected void cleanup() {}

    public final void defer() {
        this.deferred = true;
        super.cancel(true);
    }

    protected boolean isDeferred() {
        return this.deferred;
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    protected final Set<Delivery> doInBackground(@Size(min = 1) final Order... orders) {
        this.orders = orders;
        if (orders == null || orders.length == 0) {
            publishProgress(Progress.COMPLETE);
            return NO_DELIVERIES;
        }
        final Set<Delivery> deliveries = new HashSet<>(orders.length);
        @FloatRange(from = 0f, to = 1f) final float progressPerOrder = 1f / orders.length;
        @FloatRange(from = 0f, to = 1f) float progress = 0f;
        for (Order order : orders) {
            if (BuildConfig.DEBUG) Log.i(Loader.class.getSimpleName(), "Processing " + order);
            if (isCancelled()) break;
            if (order != null) {
                if (TextUtils.isEmpty(order.getUrl())) {
                    if (BuildConfig.DEBUG) Log.e(getClass().getSimpleName(), "Order without url!");
                    continue;
                }
                if (TextUtils.isEmpty(order.getDestinationFolder())) {
                    // this would lead almost inevitably to a NPE when a subclass calls "new File(order.getDestinationFolder())"
                    if (BuildConfig.DEBUG) Log.e(getClass().getSimpleName(), "Order without dest. folder!");
                    continue;
                }
                if (Build.VERSION.SDK_INT >= 24 && !LoaderService.isProtocolAllowed(order.getUri())) {
                    order = Order.toHttps(order);
                }
                boolean isEvil = this.mores != null && this.mores.isEvil(order.getUri());
                if (isEvil) {
                    if (BuildConfig.DEBUG) Log.w(Loader.class.getSimpleName(), "Won't load " + order.getUri());
                    deliveries.add(new Delivery(order, LoaderService.ERROR_EVIL, null, null));
                } else {
                    Delivery d;
                    try {
                        d = load(order, progress, progressPerOrder);
                    } catch (Throwable t) {
                        if (BuildConfig.DEBUG) Log.e(Loader.class.getSimpleName(), t.toString(), t);
                        d = new Delivery(order, LoaderService.ERROR_OTHER, null, null, t, null);
                    }
                    deliveries.add(d);
                }
            }
            progress += progressPerOrder;
            publishProgress(new Progress(progress));
        }
        publishProgress(Progress.COMPLETE);
        return deliveries;
    }

    public int getId() {
        return this.id;
    }

    @Nullable
    public final Order[] getOrders() {
        return this.orders;
    }

    /**
     * Asks the loading process to stop and <em>retain</em> the partially downloaded file.
     */
    @AnyThread
    public final synchronized void holdon() {
        if (this.stopRequested) return;
        this.stopRequested = true;
    }

    /**
     * Fulfills a particular Order.
     * @param order Order to fulfil
     * @param progressBefore progress before fulfilling the given Order
     * @param progressPerOrder progress made by fulfilling the given Order
     * @return Delivery served
     */
    @NonNull
    abstract Delivery load(@NonNull Order order, @FloatRange(from = 0, to = 1) final float progressBefore, @FloatRange(from = 0, to = 1) final float progressPerOrder);

    /** {@inheritDoc} */
    @Override
    protected final void onCancelled(@NonNull Set<Delivery> deliveries) {
        if (this.refListener != null) {
            LoaderListener l = this.refListener.get();
            if (l != null) l.done(this.id, false, deliveries);
        }
        cleanup();
    }

    /** {@inheritDoc} */
    @Override
    protected final void onCancelled() {
        if (BuildConfig.DEBUG) Log.e(getClass().getSimpleName(), "Thou shalt not call onCancelled()!");
        onCancelled(NO_DELIVERIES);
    }

    /** {@inheritDoc} */
    @Override
    protected final void onPostExecute(@NonNull Set<Delivery> deliveries) {
        if (this.refListener != null) {
            LoaderListener l = this.refListener.get();
            if (l != null) l.done(this.id, true, deliveries);
        }
        cleanup();
    }

    /** {@inheritDoc} */
    @Override
    @UiThread
    protected final void onProgressUpdate(Progress... values) {
        if (this.refListener == null || values == null || values.length == 0) return;
        //if (BuildConfig.DEBUG) Log.i(Loader.class.getSimpleName(), "onProgressUpdate(" + values[0] + ")");
        final LoaderListener l = this.refListener.get();
        if (l == null) return;
        final Progress p = values[0];
        if (p.isLiveStream()) {
            l.liveStreamDetected(this.id);
            // don't return, instead continue with progress* callback below
        }
        if (p.hasDuration()) {
            this.speed.calculate(Speed.UNIT_MS, p.getMsRecorded(), p.getMsTotal());
            l.progressAbsolute(this.id, p.getMsRecorded(), p.getMsTotal(), Math.round(this.speed.remainingSec));
        } if (p.hasCompletion()) {
            @FloatRange(from = Progress.VALUE_INVALID, to = 1f)
            float completion = p.getCompletion();
            if (completion > 1f) {
                if (BuildConfig.DEBUG) Log.e(Loader.class.getSimpleName(), "Completion value of " + completion);
                l.progressAbsolute(this.id, completion, -1L, Math.round(this.speed.remainingSec));
            } else {
                this.speed.calculate(Speed.UNIT_FRACTION, completion, 1f);
                l.progress(this.id, completion, Math.round(this.speed.remainingSec));
            }
        } else if (p.hasBuffering()) {
            l.buffering(this.id, p.getBuffering());
        } else if (p.hasMsg()) {
            //noinspection ConstantConditions
            l.message(this.id, p.getMsg(), p.error);
        } else if (p.hasResourceName()) {
            l.receivedResourceName(this.id, p.resourcename);
        } else if (p.hasNoProgress()) {
            l.noprogress(this.id);
        }
    }

    /**
     * Tells the Loader what not to load.
     * @param mores Mores
     */
    public final void setMores(@Nullable Mores mores) {
        this.mores = mores;
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    public String toString() {
        return getClass().getSimpleName() + " " + this.id;
    }

    private static class Speed {
        static final int UNIT_BYTES = 1;
        static final int UNIT_FRACTION = 2;
        static final int UNIT_MS = 3;
        static final int UNIT_UNDEFINED = 0;
        @SpeedUnit
        int unit = UNIT_UNDEFINED;
        /** the speed; its unit depends on {@link #unit}, it can be bytes/ms, ms/ms or fraction/ms */
        @FloatRange(from = 0f) float speed;
        /** estimated remaining loading time in seconds */
        float remainingSec = Float.MAX_VALUE;
        /** timestamp of the point when the download started */
        private long start;
        /** the latest measurement */
        private float latestValue;
        /** the timestamp of the latest measurement */
        private long latestTimestamp;

        void calculate(@SpeedUnit int unit, float value, float target) {
            long now = System.currentTimeMillis();
            if (unit != this.unit) {
                // we should be here exactly once when unit is non-zero for the first time
                if (this.start == 0L) this.start = now;
                this.unit = unit;
                this.latestTimestamp = now;
                this.latestValue = value;
                return;
            }
            long deltaTime = now - this.latestTimestamp;
            float deltaValue = value - this.latestValue;
            if (deltaTime == 0L) return;
            this.speed = deltaValue / (float)deltaTime;
            this.remainingSec = target > 0f ? ((target - value) / this.speed / 1_000f) : Float.MAX_VALUE;
        }

        @Retention(RetentionPolicy.SOURCE)
        @IntDef({UNIT_UNDEFINED, UNIT_BYTES, UNIT_FRACTION, UNIT_MS})
        public @interface SpeedUnit {}
    }

    /**
     * Describes the progress made.
     */
    public static final class Progress {

        public static final Progress COMPLETE = new Progress(1f);
        private static final float VALUE_INVALID = -1f;

        @NonNull
        protected static Progress buffering(@FloatRange(from = 0f, to = 100f) float buffer) {
            return new Progress(-1f, buffer);
        }

        /**
         * Generates or reuses a Progress instance bearing a completion value.
         * @param completion completion value in the 0 to 1 range
         * @param reuse Progress object to reuse (optional)
         * @return Progress object
         */
        @NonNull
        protected static Progress completing(@FloatRange(from = 0f, to = 1f) float completion, @Nullable Progress reuse) {
            if (completion > 1f) Log.e(Loader.class.getSimpleName(), "Completion " + completion + " - from " + new Throwable().getStackTrace()[1]);
            if (reuse != null) {
                reuse.completion = completion;
                return reuse;
            }
            return new Progress(completion);
        }

        @NonNull
        protected static Progress msRecorded(int ms, long msTotal) {
            return new Progress(ms, msTotal);
        }

        @NonNull
        protected static Progress msRecorded(int ms, long msTotal, boolean liveStream) {
            return new Progress(ms, msTotal, liveStream);
        }

        @NonNull
        protected static Progress msg(@NonNull String msg, boolean error) {
            return new Progress(msg, error);
        }

        protected static Progress noprogress() {return new Progress(-1, -1L);}

        protected static Progress resourcename(String resourcename) {return new Progress(resourcename);}
        /** if this is &lt; 0 and {@link #msTotal} is also &lt; 0, that means that no progress can be displayed */
        private final int msRecorded;
        /** if this is &lt; 0 and {@link #msRecorded} is also &lt; 0, that means that no progress can be displayed */
        private final long msTotal;
        @FloatRange(from = VALUE_INVALID, to = 100f)
        private final float buffering;
        @Nullable
        private final String msg;
        private final String resourcename;
        @FloatRange(from = VALUE_INVALID, to = 1f)
        private float completion;
        private boolean liveStream;
        private boolean error;

        private Progress(int msRecorded, long msTotal) {
            this(msRecorded, msTotal, false);
        }

        private Progress(int msRecorded, long msTotal, boolean liveStream) {
            super();
            this.completion = VALUE_INVALID;
            this.buffering = VALUE_INVALID;
            this.msRecorded = msRecorded;
            this.msTotal = msTotal;
            this.msg = null;
            this.resourcename = null;
            this.liveStream = liveStream;
        }

        private Progress(@FloatRange(from = 0f, to = 1f) float completion) {
            super();
            this.completion = completion;
            this.buffering = VALUE_INVALID;
            this.msRecorded = 0;
            this.msTotal = 0L;
            this.msg = null;
            this.resourcename = null;
        }

        private Progress(@FloatRange(from = VALUE_INVALID, to = 1f) float completion, float buffering) {
            super();
            this.completion = completion;
            this.buffering = buffering;
            this.msRecorded = 0;
            this.msTotal = 0L;
            this.msg = null;
            this.resourcename = null;
        }

        protected Progress(@NonNull String msg, boolean error) {
            this.completion = VALUE_INVALID;
            this.buffering = VALUE_INVALID;
            this.msRecorded = 0;
            this.msTotal = 0L;
            this.msg = msg;
            this.error = error;
            this.resourcename = null;
        }

        protected Progress(String resourcename) {
            this.completion = VALUE_INVALID;
            this.buffering = VALUE_INVALID;
            this.msRecorded = 0;
            this.msTotal = 0L;
            this.msg = null;
            this.resourcename = resourcename;
        }

        public float getBuffering() {
            return this.buffering;
        }

        @FloatRange(from = VALUE_INVALID, to = 1f)
        public float getCompletion() {
            return this.completion;
        }

        public int getMsRecorded() {
            return msRecorded;
        }

        public long getMsTotal() {
            return msTotal;
        }

        @Nullable
        public String getMsg() {
            return this.msg;
        }

        /**
         * @return true if the {@link #buffering} value is &gt;= 0
         */
        public boolean hasBuffering() {
            return this.buffering >= 0f;
        }

        /**
         * @return true if the {@link #completion} value is &gt;= 0
         */
        public boolean hasCompletion() {
            return this.completion >= 0f;
        }

        /**
         * @return {@code true} if the {@link #msRecorded} value is &gt; 0
         */
        public boolean hasDuration() {return this.msRecorded > 0;}

        public boolean hasMsg() {
            return this.msg != null;
        }

        /**
         * Returns {@code true} if no progress can be determined, probably because the host did not send a Content-Length header value.
         * @return {@code true} or {@code false}
         */
        public boolean hasNoProgress() {return this.msRecorded < 0 && this.msTotal < 0L;}

        boolean hasResourceName() {return this.resourcename != null;}

        public boolean isLiveStream() {
            return liveStream;
        }

        @Override
        @NonNull
        public String toString() {
            return "Progress{" +
                    "completion=" + completion +
                    ", buffering=" + buffering +
                    ", msRecorded=" + msRecorded + " ms" +
                    ", msTotal=" + msTotal + " ms" +
                    ", msg='" + msg + '\'' +
                    ", resourcename='" + resourcename + "'" +
                    ", liveStream=" + liveStream +
                    ", error=" + error +
                    '}';
        }
    }
}
