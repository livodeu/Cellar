/*
 * QueueManager.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.queue;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;

import net.cellar.App;
import net.cellar.BuildConfig;
import net.cellar.MainActivity;
import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.model.Wish;
import net.cellar.net.NetworkChangedReceiver;
import net.cellar.supp.DebugUtil;
import net.cellar.supp.Log;
import net.cellar.supp.UriHandler;
import net.cellar.supp.Util;
import net.cellar.worker.Inspector;

import org.jetbrains.annotations.TestOnly;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages the download queue.<br>
 * The next download will be started via {@link #nextPlease()}.
 * This will happen when <ul>
 * <li>another download has finished (see {@link net.cellar.LoaderService#done(int, Delivery)})</li>
 * <li>the user taps the action bar icon in the {@link ManageQueueActivity}</li>
 * </ul>
 */
public final class QueueManager implements NetworkChangedReceiver.ConnectivityChangedListener, SharedPreferences.OnSharedPreferenceChangeListener {

    @VisibleForTesting
    public static final int JOB_ID = 891234;
    private static final String FILE = "queue";
    private static final long MIN_CHECK_INTERVAL = 1_000L;
    /** whenever the queue has been modified, the data is stored after this many milliseconds */
    private static final long STORE_DELAY = 5_000L;
    private static final String TAG = "QueueManager";
    private static QueueManager instance = null;

    /**
     * @return the QueueManager instance
     */
    @NonNull
    public static synchronized QueueManager getInstance() {
        if (instance == null) {
            instance = new QueueManager();
        }
        return instance;
    }

    /**
     * @param js JobScheduler
     * @return true if our job exists and is in the 'pending' state, false if it doesn't exist or if it has been executed
     * @throws NullPointerException if {@code js} is {@code null}
     */
    @VisibleForTesting
    @Nullable
    public static JobInfo isJobScheduled(@NonNull JobScheduler js) {
        final List<JobInfo> jobs = js.getAllPendingJobs();
        for (JobInfo jobInfo : jobs) {
            if (jobInfo.getId() == JOB_ID) return jobInfo;
        }
        return null;
    }

    /** FIFO queue for the Wishes */
    @NonNull private final LinkedList<Wish> wishes = new LinkedList<>();
    @NonNull private final Set<Reference<Listener>> listeners = new HashSet<>();
    /** key: url, value: local file name */
    private final Map<String, String> fileNames = new HashMap<>();
    private final Handler handler = new Handler();
    private long latestCheck = 0L;
    private App app;
    private File file;
    private final Runnable storer = this::store;

    /**
     * Private constructor.
     */
    private QueueManager() {
        super();
    }

    /**
     * Adds one or more orders to the queue. Ignores {@code null} elements.<br>
     * Also ignores "content" Uris because they may not be queued due to missing permissions (they must be handled immediately by the receiving Activity).
     * @param held {@code true} to defer da download
     * @param wishes Wish(es) that will eventually be passed to {@link MainActivity}
     * @return number of Wishes that have actually been added to the queue
     */
    @RequiresApi(21)
    public int add(@Nullable final Wish... wishes) {
        //TODO make sure that added Wishes get the same treatment as in UiActivity - looking at you, UriHandler! (Don't load Youtube files as html!)
        //if (BuildConfig.DEBUG) Log.i(TAG, "addWish(" + java.util.Arrays.toString(wishes) + ")");
        if (wishes == null) return 0;
        final long now = System.currentTimeMillis();
        int count = 0;
        synchronized (this.wishes) {
            for (Wish wish : wishes) {
                if (wish == null || wish.getUri().equals(Uri.EMPTY)) continue;
                if (BuildConfig.DEBUG) Log.i(TAG, "Adding " + wish.getUri().getLastPathSegment() + " - has UriHandler: " + wish.getUriHandler());
                if ("content".equals(wish.getUri().getScheme())) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Cannot add content Uri: " + wish.getUri());
                    continue;
                }
                if (Wish.contains(this.wishes, wish)) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Already queued: " + wish);
                    continue;
                }
                wish.setTimestamp(now);
                if (wish.getUriHandler() == null) {
                    UriHandler uh = UriHandler.checkUri(wish.getUri());
                    if (uh != null) {
                        wish.setUriHandler(uh);
                        wish.setUri(uh.getUri());
                    }
                }
                if (this.wishes.offerLast(wish)) {
                    count++;
                    if (DebugUtil.TEST) {
                        app.sendBroadcast(new Intent(App.ACTION_DOWNLOAD_QUEUED));
                    }
                }
            }
        }
        if (count == 0) return 0;
        store();

        scheduleJob(null);

        notifyListeners();
        return count;
    }

    /**
     * Enqueues a deferred download.
     * @param order Order
     * @throws NullPointerException if {@code order} is {@code null}
     */
    public void addDeferredDownload(@NonNull final Order order) {
        //TODO when the download has been resumed, the file name must be removed from PREF_INSPECTOR_IGNORED!
        Inspector.toggleIgnoreFile(this.app, order.getDestinationFilename(), true);
        final Wish wish = new Wish(order.getUri());
        wish.setMime(order.getMime());
        wish.setFileName(order.getDestinationFilename());
        wish.setHeld(true);
        if (BuildConfig.DEBUG) Log.i(TAG, "Queueing deferred download: " + wish);
        add(wish);
    }

    /**
     * Adds a Listener. Does not do anything if the Listener had been added before.
     * @param listener Listener to add
     */
    void addListener(@Nullable final Listener listener) {
        if (listener == null) return;
        synchronized (this.listeners) {
            for (Reference<Listener> ref : this.listeners) {
                Listener l = ref.get();
                if (l == null) continue;
                if (l == listener) return;
            }
            this.listeners.add(new WeakReference<>(listener));
        }
    }

    /**
     * Cancels our job.
     * @param js JobScheduler (optional)
     */
    @RequiresApi(21)
    private void cancelJob(@Nullable JobScheduler js) {
        assert this.app != null;
        if (js == null) js = (JobScheduler)this.app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.cancel(JOB_ID);
        if (BuildConfig.DEBUG) Log.i(TAG, "Job cancelled.");
    }

    @TestOnly
    @VisibleForTesting
    public void clearQueue() {
        synchronized (this.wishes) {
            if (this.wishes.isEmpty()) return;
            this.wishes.clear();
        }
        notifyListeners();
    }

    @TestOnly
    @VisibleForTesting
    public void deleteFile() {
        if (!BuildConfig.DEBUG) return;
        Util.deleteFile(this.file);
    }

    /**
     * Returns all Wishes for a given host.
     * @param host host
     * @return all Wishes that point to a resource on the given host
     */
    @NonNull
    public ArrayList<Wish> getAllForHost(@Nullable String host) {
        if (host == null) return new ArrayList<>(0);
        if (host.startsWith("/")) host = host.substring(1);
        final ArrayList<Wish> list;
        synchronized (this.wishes) {
            list = new ArrayList<>(this.wishes.size());
            for (Wish wish : this.wishes) {
                Uri uri = wish.getUri();
                String wishHost = uri.getHost();
                if (host.equalsIgnoreCase(wishHost)) list.add(wish);
            }
        }
        return list;
    }

    @NonNull @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    public Deque<Wish> getWishes() {
        return this.wishes;
    }

    /**
     * Determines whether the queue contains items that are not deferred.
     * @return {@code true} if the queue contains a {@link Wish} with an unset {@link Wish#isHeld() held} flag
     */
    boolean hasNonHeldStuff() {
        synchronized (this.wishes) {
            for (Wish wish : this.wishes) {
                if (!wish.isHeld()) return true;
            }
        }
        return false;
    }

    /**
     * Determines whether the queue contains items.
     * @return {@code true} if the queue contains at least one {@link Wish}
     */
    public boolean hasQueuedStuff() {
        boolean notEmpty;
        synchronized (this.wishes) {
            notEmpty = !this.wishes.isEmpty();
        }
        return notEmpty;
    }

    /**
     * Initialisation. Must be called a) first and b) once.
     * @param ctx Context
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    public void init(@NonNull Context ctx) {
        if (this.app != null) return;
        this.app = (App)ctx.getApplicationContext();
        this.file = new File(this.app.getFilesDir(), FILE);
        load();
        NetworkChangedReceiver.getInstance().addListener(this);
        PreferenceManager.getDefaultSharedPreferences(this.app).registerOnSharedPreferenceChangeListener(this);
        if (BuildConfig.DEBUG) new Checker(this).start();
    }

    /**
     * Loads the persisted queue into memory.
     */
    private void load() {
        assert this.file != null;
        if (!this.file.isFile()) return;
        BufferedReader reader = null;
        synchronized (this.wishes) {
            this.wishes.clear();
            String line = null;
            try {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(this.file)));
                for (; ; ) {
                    line = reader.readLine();
                    if (line == null) break;
                    Wish wish = null;
                    try {
                        wish = Wish.fromString(line);
                    } catch (Exception we) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "While parsing '" + line + "': " + we.toString());
                    }
                    if (wish == null) continue;
                    this.wishes.offerLast(wish);
                }
            } catch (IOException e) {
                if (BuildConfig.DEBUG) Log.e(TAG, "While parsing '" + line + "': " + e.toString());
            } finally {
                Util.close(reader);
            }
        }
        notifyListeners();
    }

    /**
     * Moves the Wish, positioned at the given index, up to the next preceding position.
     * @param position position
     */
    void moveUp(int position) {
        if (position < 1) return;
        synchronized (this.wishes) {
            if (position >= this.wishes.size()) return;
            Wish wish = this.wishes.get(position);
            Wish previous = this.wishes.get(position - 1);
            this.wishes.set(position - 1, wish);
            this.wishes.set(position, previous);
        }
        notifyListeners();
        this.handler.removeCallbacks(this.storer);
        this.handler.postDelayed(this.storer, STORE_DELAY);
    }

    @TestOnly
    public void moveUp(int position, final int steps) {
        if (position < steps) return;
        synchronized (this.wishes) {
            if (position >= this.wishes.size()) return;
            for (int i = 0; i < steps; i++, position--) {
                Wish wish = this.wishes.get(position);
                Wish previous = this.wishes.get(position - 1);
                this.wishes.set(position - 1, wish);
                this.wishes.set(position, previous);
            }
        }
        notifyListeners();
        this.handler.removeCallbacks(this.storer);
        this.handler.postDelayed(this.storer, STORE_DELAY);
    }

    /**
     * Initiates the download of the next queued item.
     * @return {@code true} if a Wish has been taken from the queue and the {@link MainActivity} has been invoked
     */
    public synchronized boolean nextPlease() {
        return nextPlease(false);
    }

    /**
     * Initiates the download of the next queued item.
     * This is at most done every {@link #MIN_CHECK_INTERVAL} milliseconds.
     * @return {@code true} if a Wish has been taken from the queue and the {@link MainActivity} has been invoked
     */
    public synchronized boolean nextPlease(boolean force) {
        assert this.app != null;
        long now = System.currentTimeMillis();
        if (!force && (now - this.latestCheck < MIN_CHECK_INTERVAL)) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Skipping because latest check was only " + ((now - this.latestCheck) / 1000) + " s ago");
            return false;
        }
        this.latestCheck = now;
        final int n;
        synchronized (this.wishes) {
            n = this.wishes.size();
            if (n == 0) return false;
        }
        Intent networkSituation = this.app.registerReceiver(null, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));
        if (networkSituation == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Did not get network situation!");
            scheduleJob(null);
            return false;
        }
        NetworkInfo ni = networkSituation.getParcelableExtra("networkInfo");
        if (ni == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Did not get network info!");
            scheduleJob(null);
            return false;
        }
        if (ni.getState() != NetworkInfo.State.CONNECTED) {
            if (BuildConfig.DEBUG) Log.i(TAG, "Still no network. Sigh. El cheapo SIM? (" + ni.toString() + ")");
            scheduleJob(null);
            return false;
        }
        if (this.app.hasActiveLoaders()) {
            if (BuildConfig.DEBUG) Log.i(TAG, "Cannot pop anything from the queue - app is still busy.");
            scheduleJob(null);
            return false;
        }
        final Wish wish;
        final int remaining;
        synchronized (this.wishes) {
            int pickThisOne = -1;
            for (int i = 0; i < n && pickThisOne < 0; i++) {
                if (this.wishes.get(i).isHeld()) continue;
                pickThisOne = i;
            }
            if (pickThisOne < 0) {
                //if (BuildConfig.DEBUG) Log.i(TAG, "All queued downloads have been deferred.");
                return false;
            }
            wish = this.wishes.remove(pickThisOne);
            remaining = this.wishes.size();
        }
        synchronized (this.fileNames) {
            boolean fileNameFound = false;
            for (Map.Entry<String, String> e : this.fileNames.entrySet()) {
                if (e.getKey().equalsIgnoreCase(wish.getUri().toString())) {
                    wish.setFileName(e.getValue());
                    if (BuildConfig.DEBUG) Log.i(TAG, "Uri " + e.getKey() + " will be stored in " + e.getValue());
                    fileNameFound = true;
                    break;
                }
            }
            if (BuildConfig.DEBUG && !fileNameFound) Log.i(TAG, "No file name found for Uri " + wish.getUri());
        }
        final Intent intentMain = new Intent(this.app, MainActivity.class);
        intentMain.setAction(MainActivity.ACTION_PROCESS_WISH);
        intentMain.putExtra(MainActivity.EXTRA_WISH, wish);
        intentMain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (BuildConfig.DEBUG) Log.i(TAG, "Ready to load " + intentMain + "; " + remaining + " download(s) remaining");
        this.app.startActivity(intentMain);
        this.handler.removeCallbacks(this.storer);
        store();
        if (remaining > 0) {
            scheduleJob(null);
        } else {
            cancelJob(null);
        }
        notifyListeners();
        return true;
    }

    /**
     * Calls {@link Listener#queueChanged()} on the listeners.
     */
    private void notifyListeners() {
        synchronized (this.listeners) {
            for (Reference<Listener> ref : this.listeners) {
                Listener l = ref.get();
                if (l == null) continue;
                l.queueChanged();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onConnectivityChanged(@NonNull NetworkInfo.State old, @NonNull NetworkInfo.State state) {
        if (old != NetworkInfo.State.CONNECTED && state == NetworkInfo.State.CONNECTED) {
            nextPlease();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (App.PREF_ALLOW_METERED.equals(key)) {
            // the user has changed whether the app may download over metered networks -> re-schedule the job if it is currently scheduled
            JobScheduler js = (JobScheduler)this.app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            JobInfo jobInfo = isJobScheduled(js);
            if (jobInfo == null) return;
            cancelJob(js);
            scheduleJob(js);
        }
    }

    /**
     * Removes several Wishes.
     * @param removeUs Wishes to remove
     * @return number of Wishes removed
     */
    public int remove(final Wish... removeUs) {
        if (BuildConfig.DEBUG) Log.i(TAG, "remove(" + Arrays.toString(removeUs) + ")");
        if (removeUs == null) return 0;
        int counter = 0;
        synchronized (this.wishes) {
            for (Wish removeMe : removeUs) {
                boolean removed = this.wishes.remove(removeMe);
                if (removed) counter++;
            }
        }
        if (counter > 0) {
            notifyListeners();
            this.handler.removeCallbacks(this.storer);
            this.handler.postDelayed(this.storer, STORE_DELAY);
        }
        return counter;
    }

    /**
     * Removes a download order.
     * @param wish Wish to remove
     * @return {@code true} if the element has been indeed removed
     */
    public boolean remove(Wish wish) {
        if (wish == null) return false;
        boolean removed;
        synchronized (this.wishes) {
            removed = this.wishes.remove(wish);
        }
        if (removed) {
            notifyListeners();
            this.handler.removeCallbacks(this.storer);
            // in tests, store immediately because the process might be killed shortly
            if (DebugUtil.TEST) this.handler.post(this.storer);
            else this.handler.postDelayed(this.storer, STORE_DELAY);
        }
        return removed;
    }

    /**
     * Removes a Listener.
     * @param listener Listener to remove
     */
    void removeListener(@Nullable final Listener listener) {
        if (listener == null) return;
        synchronized (this.listeners) {
            Reference<Listener> toRemove = null;
            for (Reference<Listener> ref : this.listeners) {
                Listener l = ref.get();
                if (l == listener) {
                    toRemove = ref;
                    break;
                }
            }
            if (toRemove != null) {
                this.listeners.remove(toRemove);
            }
        }
    }

    /**
     * Schedules the job that starts a {@link QueueJobService} which would then, in turn, invoke {@link #nextPlease()}.
     * QueueManager cannot be a JobService itself because those are instantiated and destroyed by the droid while QueueManager is a singleton.<br>
     * The job will only be scheduled if there are tasks in the queue.
     * The job will not be periodic.
     */
    @RequiresApi(21)
    @RequiresPermission("android.permission.RECEIVE_BOOT_COMPLETED")
    private synchronized void scheduleJob(@Nullable JobScheduler js) {
        assert this.app != null;
        if (js == null) js = (JobScheduler)this.app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (isJobScheduled(js) != null) return;

        if (!hasQueuedStuff()) {
            if (BuildConfig.DEBUG) Log.i(TAG, "Not scheduling job. Queue is empty.");
            return;
        }

        final JobInfo.Builder jib = new JobInfo.Builder(JOB_ID, new ComponentName(this.app, QueueJobService.class));
        jib.setOverrideDeadline(6 * 3_600_000L);
        jib.setPersisted(true);
        jib.setBackoffCriteria(20_000L, JobInfo.BACKOFF_POLICY_LINEAR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            jib.setRequiresStorageNotLow(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (Build.VERSION.SDK_INT < 31) {
                jib.setImportantWhileForeground(true);
            }
        }
        boolean allowMetered = PreferenceManager.getDefaultSharedPreferences(this.app).getBoolean(App.PREF_ALLOW_METERED, App.PREF_ALLOW_METERED_DEFAULT);
        jib.setRequiredNetworkType(allowMetered ? JobInfo.NETWORK_TYPE_ANY : JobInfo.NETWORK_TYPE_UNMETERED);
        if (js.schedule(jib.build()) == JobScheduler.RESULT_SUCCESS) {
            //if (BuildConfig.DEBUG) Log.i(TAG, "Job scheduled.");
            return;
        }
        if (BuildConfig.DEBUG) Log.e(TAG, "Failed to schedule job!");
    }

    /**
     * Passes information that the resource loaded from the given url will be stored in the given file.<br>
     * The url might never appear here, because this is called whenever the host returns a Content-Disposition header.<br>
     * But this is important when the user interrupts (defers) a download - without this information the download cannot be resumed properly if the file name changed.
     * @param url URL
     * @param fileName local file name (in the downloads folder)
     */
    @AnyThread
    public void setFileName(@NonNull final String url, @NonNull final String fileName) {
        if (BuildConfig.DEBUG) Log.i(TAG, "setFileName(" + url + ", " + fileName + ")");
        synchronized (this.fileNames) {
            this.fileNames.put(url, fileName);
        }
    }

    /**
     * Persists the current queue.
     */
    private void store() {
        assert this.file != null;
        synchronized (this.wishes) {
            if (this.wishes.isEmpty()) {
                Util.deleteFile(this.file);
                return;
            }
            BufferedWriter writer = null;
            File tmp = null;
            try {
                tmp = File.createTempFile("tmpqueue", null);
                writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp)));
                for (Wish wish : this.wishes) {
                    writer.write(wish.toString());
                    writer.newLine();
                }
                Util.close(writer);
                writer = null;
                if (!tmp.renameTo(this.file)) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Failed to rename " + tmp + " to " + this.file);
                    Util.deleteFile(tmp);
                }
            } catch (Exception e) {
                if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
                Util.deleteFile(tmp);
            } finally {
                Util.close(writer);
            }
        }
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public String toString() {
        int n;
        synchronized (this.wishes) {
            n = this.wishes.size();
        }
        return getClass().getSimpleName() + " with " + n + " queued element(s)";
    }

    /**
     * Toggles the held flag for the Wish at the given position.
     * @param position position
     */
    void toggleHeld(final int position) {
        synchronized (this.wishes) {
            if (position < 0 || position >= this.wishes.size()) return;
            Wish wish = this.wishes.get(position);
            wish.setHeld(!wish.isHeld());
        }
        notifyListeners();
        this.handler.removeCallbacks(this.storer);
        this.handler.postDelayed(this.storer, STORE_DELAY);
    }

    /**
     * Gets informed when the download queue has been modified.
     */
    interface Listener {
        /**
         * The download queue has been modified.
         */
        void queueChanged();
    }

    @SuppressWarnings("BusyWait")
    private static class Checker extends Thread {

        private final Reference<QueueManager> r;

        private Checker(@NonNull QueueManager qm) {
            super();
            this.r = new WeakReference<>(qm);
            setPriority(Thread.MIN_PRIORITY);
        }

        /** {@inheritDoc} */
        @Override
        public void run() {
            if (!BuildConfig.DEBUG) return;
            for (;;) {
                QueueManager qm = this.r.get();
                if (qm == null) break;
                JobScheduler js = (JobScheduler)qm.app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                JobInfo j = isJobScheduled(js);
                if (j != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        android.net.NetworkRequest n = j.getRequiredNetwork();
                        Log.i("Q", "Job is scheduled: required network: " + (n != null ? n.toString() : "<null>") + ", max delay: " + j.getMaxExecutionDelayMillis() + " ms");
                    } else {
                        Log.i("Q", "Job is scheduled.");
                    }
                }
                try {
                    sleep(120_000L);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            Log.w("Q", "Checker stopped.");
        }
    }
}
