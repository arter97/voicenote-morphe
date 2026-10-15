package app.morphe.extension.voicenote.patches;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.sec.android.app.voicenote.common.util.AppResources;
import com.sec.android.app.voicenote.common.util.SceneKeeper;
import com.sec.android.app.voicenote.data.CheckedItemProvider;
import com.sec.android.app.voicenote.data.ListFragmentStateProvider;
import com.sec.android.app.voicenote.data.ListFragmentStateProviderFactory;
import com.sec.android.app.voicenote.engine.FastConvertController;
import com.sec.android.app.voicenote.ui.actionbar.AddRemoveOptionMenuHelper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("unused")
public final class BatchTranscribePatch {
    private static final String TAG = "BatchTranscribePatch";

    /**
     * Bundle key of the selected recording ids (DialogConstant.BUNDLE_START_CONVERT_WITH_LIST_FILE).
     */
    private static final String KEY_LIST_FILE = "start_convert_with_list_file";

    /**
     * FastConvertController / TranslatorState states.
     */
    private static final int STATE_WAITING = 12;
    private static final int STATE_CONVERT_DONE = 15;
    private static final int STATE_CONVERT_ERROR = 16;
    private static final int STATE_CANCELED = 18;

    /**
     * Error of a full storage (AiConstants.STORAGE_FULL_ERROR). Its dialog is still shown
     * during a batch, as every following recording would fail the same way.
     */
    private static final String STORAGE_FULL_ERROR = "STORAGE_FULL_ERROR";

    /**
     * How long a prepared selection is matched against new transcriptions. Covers the time
     * the language dialog may stay open.
     */
    private static final long PREPARED_BATCH_TIMEOUT_MS = 10 * 60 * 1000;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // region Selection menu, main thread only.

    /**
     * Recording that the stock eligibility check is currently asked about, or -1.
     */
    private static long probeId = -1;

    /**
     * Eligibility of the recordings checked during the current selection. A recording
     * only becomes ineligible by being transcribed, which drops its entry.
     */
    private static final Map<Long, Boolean> eligibilityCache = new HashMap<>();

    private static WeakReference<AddRemoveOptionMenuHelper> lastHelper = new WeakReference<>(null);

    // endregion

    // region Transcription queue, guarded by queueLock.

    private static final Object queueLock = new Object();

    /**
     * Selection about to be transcribed as a batch, in queue order, or null.
     */
    private static Set<Long> preparedBatch;
    private static long preparedBatchTime;

    /**
     * Selection for the auto-detect path of TranscribeExecutor, which only queues the first
     * recording itself.
     */
    private static long[] autoDetectBatch;

    /**
     * Recordings queued as part of the current batch, and those of them not finished yet.
     */
    private static final Set<Long> batchIds = new HashSet<>();
    private static final Set<Long> batchRemaining = new HashSet<>();
    private static int batchDoneCount;
    private static int batchFailedCount;

    /**
     * Recordings queued by any path that are not finished yet.
     */
    private static final Set<Long> queuedIds = new LinkedHashSet<>();

    /**
     * Recordings cancelled through FastConvertController.cancelFastConvert(), which ends
     * them as cancelled. Any other cancellation is the worker giving up after an error.
     */
    private static final Set<Long> userCancelledIds = new HashSet<>();

    /**
     * Flags of queued recordings. The stock queue keeps only the language and starts
     * every waiting recording without them.
     */
    private static final Map<Long, boolean[]> queuedFlags = new HashMap<>();

    private static boolean stateListenerRegistered;

    /**
     * Batch recordings cancelled by the user, which no longer count towards the batch size.
     */
    private static int batchCancelledCount;

    /**
     * Progress of the batch recording being transcribed.
     */
    private static long progressId = -1;
    private static int progressPercent;

    /**
     * Strongly held, as the controller only keeps weak references to its listeners.
     */
    private static final FastConvertController.FastConvertStateChangeListener stateListener =
            new FastConvertController.FastConvertStateChangeListener() {
                @Override
                public void notifyFastConvertStatusChanged(Long id, Integer state, String error) {
                    onStateChanged(id, state, error);
                }

                @Override
                public void notifyFastConvertProgressUpdated(long id, int percent) {
                    onProgressChanged(id, percent);
                }
            };

    // endregion

    // region Selection menu

    /**
     * Injection point.
     * <p>
     * Replaces AddRemoveOptionMenuHelper.needInflateTranscribeMenu(), which only shows
     * Transcribe for a single eligible recording while nothing else is transcribing. Shows
     * it if any checked recording passes the stock check of a single recording.
     */
    public static boolean needInflateTranscribeMenu(AddRemoveOptionMenuHelper helper) {
        lastHelper = new WeakReference<>(helper);

        List<Long> checkedIds = new ArrayList<>(CheckedItemProvider.getCheckedItems());
        if (checkedIds.isEmpty()) {
            eligibilityCache.clear();
            return helper.needInflateTranscribeMenu$morphe();
        }

        return anyEligible(helper, checkedIds);
    }

    /**
     * Injection point.
     * <p>
     * Replaces CheckedItemProvider.getCheckedItems() in the stock check.
     */
    public static ArrayList<Long> getCheckedItems() {
        if (probeId != -1) {
            ArrayList<Long> ids = new ArrayList<>(1);
            ids.add(probeId);
            return ids;
        }
        return CheckedItemProvider.getCheckedItems();
    }

    /**
     * Injection point.
     * <p>
     * Replaces CheckedItemProvider.getCheckedItemCount() in the stock check.
     */
    public static int getCheckedItemCount() {
        return probeId != -1 ? 1 : CheckedItemProvider.getCheckedItemCount();
    }

    /**
     * Injection point.
     * <p>
     * Replaces FastConvertController.numberFilesConverting() in the stock check, so that
     * more recordings can be added to a running queue.
     */
    public static int numberFilesConverting(FastConvertController controller) {
        return probeId != -1 ? 0 : controller.numberFilesConverting();
    }

    /**
     * Injection point.
     * <p>
     * Filters the transcribe and re-transcribe items looked up by
     * AddRemoveOptionMenu.updateSelectMenu(). With several recordings checked, the stock
     * method rebuilds the bottom menu whenever either item is present, as it never shows
     * them for more than one recording. Hide them from it, and let
     * {@link #getRenameItem} decide instead.
     */
    public static MenuItem getTranscribeItem(MenuItem item) {
        return CheckedItemProvider.getCheckedItemCount() > 1 ? null : item;
    }

    /**
     * Injection point.
     * <p>
     * Filters the rename item looked up by AddRemoveOptionMenu.updateSelectMenu(), which
     * rebuilds the bottom menu for several checked recordings only if it is present. Return
     * an item when the menu must be rebuilt: when a single-selection item is still present,
     * or when Transcribe must appear or disappear.
     *
     * @param transcribeItem   Stock transcribe item, before {@link #getTranscribeItem}.
     * @param reTranscribeItem Stock re-transcribe item, before {@link #getTranscribeItem}.
     */
    public static MenuItem getRenameItem(Menu menu, MenuItem transcribeItem,
                                         MenuItem reTranscribeItem, MenuItem renameItem) {
        int checkedCount = CheckedItemProvider.getCheckedItemCount();
        if (checkedCount == 0) {
            eligibilityCache.clear();
        }
        if (checkedCount <= 1 || renameItem != null) {
            return renameItem;
        }
        if (reTranscribeItem != null) {
            return reTranscribeItem;
        }

        AddRemoveOptionMenuHelper helper = lastHelper.get();
        if (helper == null) {
            return null;
        }

        boolean shown = transcribeItem != null;
        if (shown == anyEligible(helper, new ArrayList<>(CheckedItemProvider.getCheckedItems()))) {
            return null;
        }
        if (transcribeItem != null) {
            return transcribeItem;
        }
        return menu.size() > 0 ? menu.getItem(0) : null;
    }

    private static boolean anyEligible(AddRemoveOptionMenuHelper helper, List<Long> ids) {
        for (Long id : ids) {
            if (isEligible(helper, id, true)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEligible(AddRemoveOptionMenuHelper helper, long id, boolean useCache) {
        if (FastConvertController.getInstance().isFileFastConvertingOrWaiting(id)) {
            return false;
        }

        if (useCache) {
            Boolean cached = eligibilityCache.get(id);
            if (cached != null) {
                return cached;
            }
        }

        boolean eligible;
        probeId = id;
        try {
            eligible = helper.needInflateTranscribeMenu$morphe();
        } catch (Exception ex) {
            Log.e(TAG, "Eligibility check failed: " + id, ex);
            eligible = false;
        } finally {
            probeId = -1;
        }

        eligibilityCache.put(id, eligible);
        return eligible;
    }

    // endregion

    // region Starting a batch

    /**
     * Injection point.
     * <p>
     * Called with the bundle RunOptionMenuHelper.getBundleHandleTranscribe() returns for
     * the Transcribe button, which holds every checked recording. Keeps the eligible ones
     * in list order. A null bundle starts nothing.
     */
    public static Bundle prepareBatch(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        long[] checkedIds = bundle.getLongArray(KEY_LIST_FILE);
        if (checkedIds == null || checkedIds.length == 0) {
            return bundle;
        }

        // Check again, as the cache may be stale.
        eligibilityCache.clear();
        AddRemoveOptionMenuHelper helper = lastHelper.get();
        List<Long> eligibleIds = new ArrayList<>(checkedIds.length);
        for (long id : checkedIds) {
            if (helper == null ? !FastConvertController.getInstance().isFileFastConvertingOrWaiting(id)
                    : isEligible(helper, id, false)) {
                eligibleIds.add(id);
            }
        }

        int skippedCount = checkedIds.length - eligibleIds.size();
        if (eligibleIds.isEmpty()) {
            showToast(checkedIds.length == 1
                    ? "This recording can't be transcribed"
                    : "None of the selected recordings can be transcribed");
            return null;
        }
        if (skippedCount > 0) {
            showToast("Skipped " + recordings(skippedCount) + " that can't be transcribed");
        }

        sortByListOrder(eligibleIds);

        long[] batch = new long[eligibleIds.size()];
        for (int i = 0; i < batch.length; i++) {
            batch[i] = eligibleIds.get(i);
        }
        bundle.putLongArray(KEY_LIST_FILE, batch);

        synchronized (queueLock) {
            if (batch.length > 1) {
                preparedBatch = new LinkedHashSet<>(eligibleIds);
                preparedBatchTime = System.currentTimeMillis();
            } else {
                preparedBatch = null;
            }
        }

        Log.i(TAG, "Transcribing " + batch.length + " recordings, skipped " + skippedCount);
        return bundle;
    }

    private static void sortByListOrder(List<Long> ids) {
        List<Long> listOrder;
        try {
            listOrder = ListOrder.get();
        } catch (Throwable ex) {
            // Older app versions have no list state provider. Keep the selection order.
            Log.w(TAG, "No list order", ex);
            return;
        }
        if (listOrder == null || listOrder.isEmpty()) {
            return;
        }

        Map<Long, Integer> positions = new HashMap<>(listOrder.size());
        for (int i = 0; i < listOrder.size(); i++) {
            positions.put(listOrder.get(i), i);
        }
        // Stable, so recordings missing from the list stay in selection order at the end.
        Collections.sort(ids, (a, b) -> Integer.compare(
                positions.getOrDefault(a, Integer.MAX_VALUE),
                positions.getOrDefault(b, Integer.MAX_VALUE)));
    }

    /**
     * Separate class, as its app classes are missing in older app versions.
     */
    private static final class ListOrder {
        static List<Long> get() {
            ListFragmentStateProvider provider = ListFragmentStateProviderFactory.INSTANCE
                    .getProvider(SceneKeeper.getInstance().getScene());
            return provider == null ? null : provider.getIDs();
        }
    }

    /**
     * Injection point.
     * <p>
     * Called at the start of TranscribeExecutor.executeTranscribe().
     */
    public static void onExecuteTranscribe(Bundle bundle) {
        long[] ids = bundle == null ? null : bundle.getLongArray(KEY_LIST_FILE);
        synchronized (queueLock) {
            if (ids != null && ids.length > 1) {
                autoDetectBatch = ids;
            } else {
                // Another transcription, so a batch whose language dialog was dismissed
                // is not started anymore.
                autoDetectBatch = null;
                preparedBatch = null;
            }
        }
    }

    /**
     * Injection point.
     * <p>
     * Replaces the FastConvertController.addFastConvertItem() call of
     * TranscribeExecutor.executeTranscribeWithAutoDetect(), which only queues the first
     * of the selected recordings. Each recording detects its own language.
     */
    public static void addFastConvertItems(FastConvertController controller, long id, String language,
                                           boolean isForceTranscribeWithLanguage,
                                           boolean isReTranscribe, boolean isAutoSummary) {
        long[] ids;
        synchronized (queueLock) {
            ids = autoDetectBatch;
            autoDetectBatch = null;
        }
        if (ids == null || ids[0] != id) {
            ids = new long[]{id};
        }

        for (long batchId : ids) {
            controller.addFastConvertItem(batchId, language, isForceTranscribeWithLanguage,
                    isReTranscribe, isAutoSummary);
        }
    }

    // endregion

    // region Queue

    /**
     * Injection point.
     * <p>
     * Called at the start of FastConvertController.addFastConvertItem(), the only way a
     * recording is queued.
     */
    public static void onAddFastConvertItem(long id, String language, boolean isForceTranscribeWithLanguage,
                                            boolean isReTranscribe, boolean isAutoSummary) {
        synchronized (queueLock) {
            if (!stateListenerRegistered) {
                FastConvertController.getInstance().registerFastConvertStateChangeListener(stateListener);
                stateListenerRegistered = true;
            }

            queuedIds.add(id);
            userCancelledIds.remove(id);
            queuedFlags.put(id, new boolean[]{isForceTranscribeWithLanguage, isAutoSummary});

            if (preparedBatch != null
                    && System.currentTimeMillis() - preparedBatchTime > PREPARED_BATCH_TIMEOUT_MS) {
                preparedBatch = null;
            }

            if (preparedBatch != null && preparedBatch.remove(id)) {
                if (preparedBatch.isEmpty()) {
                    preparedBatch = null;
                }
                if (batchRemaining.isEmpty()) {
                    // New batch.
                    batchIds.clear();
                    batchDoneCount = 0;
                    batchFailedCount = 0;
                    batchCancelledCount = 0;
                    progressId = -1;
                }
                batchIds.add(id);
                batchRemaining.add(id);
            } else if (batchRemaining.isEmpty()) {
                batchIds.remove(id);
                return;
            } else if (!batchRemaining.contains(id)) {
                // Joins the running batch.
                batchIds.add(id);
                batchRemaining.add(id);
            } else {
                return;
            }
        }

        BatchNotification.update(false);
    }

    /**
     * Injection point.
     * <p>
     * Called at the start of FastConvertController.startFastConvert(). Restores the
     * force-language flag of a recording that waited in the queue.
     */
    public static boolean getForceTranscribeWithLanguage(Object controller, long id, String path,
                                                         String session, String language,
                                                         boolean isForceTranscribeWithLanguage,
                                                         boolean isAutoSummary) {
        synchronized (queueLock) {
            boolean[] flags = queuedFlags.get(id);
            return isForceTranscribeWithLanguage || (flags != null && flags[0]);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called at the start of FastConvertController.startFastConvert(). Restores the
     * auto-summary flag of a recording that waited in the queue.
     */
    public static boolean getAutoSummary(Object controller, long id, String path,
                                         String session, String language,
                                         boolean isForceTranscribeWithLanguage,
                                         boolean isAutoSummary) {
        synchronized (queueLock) {
            boolean[] flags = queuedFlags.remove(id);
            return isAutoSummary || (flags != null && flags[1]);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called at the start of FastConvertController.cancelFastConvert(Long).
     */
    public static void onCancelFastConvert(Long id) {
        if (id == null) {
            return;
        }
        synchronized (queueLock) {
            userCancelledIds.add(id);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called at the start of FastConvertService.cancelFastConvert(), the Cancel button of
     * the ongoing notification, which only cancels the recording being transcribed.
     * Cancel the waiting ones first, so the queue does not move on to them.
     */
    public static void cancelWaitingItems() {
        List<Long> ids;
        synchronized (queueLock) {
            ids = new ArrayList<>(queuedIds);
        }

        FastConvertController controller = FastConvertController.getInstance();
        for (Long id : ids) {
            if (Boolean.TRUE.equals(controller.isFileInWaitingList(id))) {
                Log.i(TAG, "Cancelling waiting recording " + id);
                controller.cancelFastConvert(id);
            }
        }
    }

    private static void onStateChanged(Long id, Integer state, String error) {
        if (id == null || state == null) {
            return;
        }
        int convertState = state;
        if (convertState != STATE_CONVERT_DONE && convertState != STATE_CONVERT_ERROR
                && convertState != STATE_CANCELED) {
            return;
        }

        if (convertState == STATE_CONVERT_DONE) {
            mainHandler.post(() -> eligibilityCache.remove(id));
        }

        boolean finished;
        String summary = null;
        synchronized (queueLock) {
            queuedIds.remove(id);
            queuedFlags.remove(id);
            boolean userCancelled = userCancelledIds.remove(id);

            if (!batchRemaining.remove(id)) {
                return;
            }
            if (convertState == STATE_CONVERT_DONE) {
                batchDoneCount++;
            } else if (userCancelled) {
                batchCancelledCount++;
            } else {
                // A failed recording ends as cancelled, then reports the error.
                batchFailedCount++;
            }
            if (progressId == id) {
                progressId = -1;
            }

            finished = batchRemaining.isEmpty();
            if (finished) {
                summary = getBatchSummary(batchDoneCount, batchFailedCount);
                Log.i(TAG, "Batch finished, transcribed: " + batchDoneCount
                        + " failed: " + batchFailedCount);
            }
        }

        if (finished) {
            BatchNotification.finish(summary);
            if (summary != null) {
                showToast(summary);
            }
        } else {
            BatchNotification.update(true);
        }
    }

    private static void onProgressChanged(long id, int percent) {
        synchronized (queueLock) {
            if (!batchRemaining.contains(id) || percent < 0) {
                return;
            }
            progressId = id;
            progressPercent = Math.min(percent, 100);
        }

        BatchNotification.update(false);
    }

    private static String getBatchSummary(int doneCount, int failedCount) {
        if (failedCount == 0) {
            return doneCount == 0 ? null : "Transcribed " + recordings(doneCount);
        }
        if (doneCount == 0) {
            return "Couldn't transcribe " + recordings(failedCount);
        }
        return "Transcribed " + recordings(doneCount) + ". " + failedCount
                + " couldn't be transcribed.";
    }

    /**
     * Notification of a running batch, separate from the app's notification of the
     * recording being transcribed.
     */
    private static final class BatchNotification {
        private static final String CHANNEL_ID = "morphe_batch_transcription";
        private static final int NOTIFICATION_ID = 0x4D425452;

        /**
         * Action of the notification's Cancel button. Handled by a receiver registered at
         * runtime, as the original manifest is kept by mount-based installs.
         */
        private static final String ACTION_CANCEL =
                "app.morphe.extension.voicenote.BATCH_TRANSCRIBE_CANCEL";

        /**
         * Action of the app's own notification Cancel button
         * (IntentAction.BACKGROUND_VOICENOTE_FAST_CONVERT_CANCEL).
         */
        private static final String ACTION_STOCK_CANCEL =
                "com.sec.android.app.voicenote.fast_convert_cancel";

        /**
         * The progress notification is ongoing, so it is removed after this long without
         * an update in case the app process dies mid-batch. Updates come every few seconds.
         */
        private static final long STALE_TIMEOUT_MS = 10 * 60 * 1000;

        private static final long MIN_UPDATE_INTERVAL_MS = 1000;
        private static final long MAX_UPDATE_INTERVAL_MS = 30 * 1000;

        private static boolean initialized;
        private static int smallIcon;
        private static PendingIntent contentIntent;
        private static PendingIntent cancelIntent;

        // Last posted state, to throttle progress updates.
        private static int lastFinished = -1;
        private static int lastTotal = -1;
        private static int lastPercent = -1;
        private static long lastPostTime;

        private static synchronized void initialize(Context context) {
            if (initialized) {
                return;
            }
            initialized = true;

            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Batch transcription", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);

            String packageName = context.getPackageName();
            smallIcon = context.getResources().getIdentifier(
                    "stat_notify_voicerecorder", "drawable", packageName);
            if (smallIcon == 0) {
                smallIcon = context.getApplicationInfo().icon;
            }

            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                contentIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, launchIntent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            }

            cancelIntent = PendingIntent.getBroadcast(context, NOTIFICATION_ID,
                    new Intent(ACTION_CANCEL).setPackage(packageName),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    Log.i(TAG, "Cancelling batch from notification");
                    cancelWaitingItems();
                    // The app's own Cancel handles the recording being transcribed.
                    receiverContext.sendBroadcast(new Intent(ACTION_STOCK_CANCEL)
                            .setPackage(receiverContext.getPackageName()));
                }
            }, new IntentFilter(ACTION_CANCEL), Context.RECEIVER_NOT_EXPORTED);
        }

        /**
         * @param force Post even if only the progress of the current recording changed
         *              recently.
         */
        static void update(boolean force) {
            int finished;
            int total;
            int percent;
            long currentId;
            synchronized (queueLock) {
                if (batchRemaining.isEmpty()) {
                    return;
                }
                finished = batchDoneCount + batchFailedCount;
                total = batchIds.size() - batchCancelledCount;
                percent = progressId != -1 ? progressPercent : 0;
                currentId = progressId;
            }

            FastConvertController controller = FastConvertController.getInstance();
            if (currentId == -1) {
                currentId = controller.getCurrentFastConvertId();
            }
            String name = null;
            synchronized (queueLock) {
                if (batchRemaining.contains(currentId)
                        && controller.getCurrentFastConvertId() == currentId) {
                    name = controller.getCurrentFastConvertName();
                }
            }

            synchronized (BatchNotification.class) {
                long now = System.currentTimeMillis();
                boolean countsChanged = finished != lastFinished || total != lastTotal;
                long sinceLastPost = now - lastPostTime;
                if (!force && !countsChanged && (sinceLastPost < MIN_UPDATE_INTERVAL_MS
                        || (percent == lastPercent && sinceLastPost < MAX_UPDATE_INTERVAL_MS))) {
                    return;
                }
                lastFinished = finished;
                lastTotal = total;
                lastPercent = percent;
                lastPostTime = now;
            }

            try {
                Context context = AppResources.getAppContext();
                initialize(context);

                int max = Math.max(total, 1) * 100;
                Notification.Builder builder = newBuilder(context)
                        .setContentTitle("Transcribing recordings (" + finished + "/" + total + ")")
                        .setProgress(max, Math.min(finished * 100 + percent, max), false)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setCategory(Notification.CATEGORY_PROGRESS)
                        .setTimeoutAfter(STALE_TIMEOUT_MS)
                        .addAction(new Notification.Action.Builder(
                                null, "Cancel all", cancelIntent).build());
                if (name != null && !name.isEmpty()) {
                    builder.setContentText(name);
                }

                context.getSystemService(NotificationManager.class)
                        .notify(NOTIFICATION_ID, builder.build());
            } catch (Exception ex) {
                Log.e(TAG, "Failed to update batch notification", ex);
            }
        }

        /**
         * @param summary Result of the batch, or null to only remove the notification.
         */
        static void finish(String summary) {
            synchronized (BatchNotification.class) {
                lastFinished = -1;
                lastTotal = -1;
                lastPercent = -1;
                lastPostTime = 0;
            }

            try {
                Context context = AppResources.getAppContext();
                initialize(context);
                NotificationManager manager = context.getSystemService(NotificationManager.class);

                if (summary == null) {
                    manager.cancel(NOTIFICATION_ID);
                    return;
                }

                manager.notify(NOTIFICATION_ID, newBuilder(context)
                        .setContentTitle("Batch transcription finished")
                        .setContentText(summary)
                        .setAutoCancel(true)
                        .build());
            } catch (Exception ex) {
                Log.e(TAG, "Failed to finish batch notification", ex);
            }
        }

        private static Notification.Builder newBuilder(Context context) {
            Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(smallIcon)
                    .setShowWhen(false);
            if (contentIntent != null) {
                builder.setContentIntent(contentIntent);
            }
            return builder;
        }
    }

    // endregion

    // region List

    /**
     * Injection point.
     * <p>
     * Called at the start of the list's progress text lookup. Recordings waiting in the
     * queue otherwise read "Transcribing…" like the one actually being transcribed.
     *
     * @return Text for a waiting recording, or null for the stock text.
     */
    public static String getProgressText(long id) {
        FastConvertController.FastConvertItem item =
                FastConvertController.getInstance().getFastConvertList().get(id);
        if (item != null && item.getConvertState() == STATE_WAITING) {
            return "Waiting to transcribe";
        }
        return null;
    }

    /**
     * Injection point.
     * <p>
     * Older app versions pass the id as a Long.
     */
    public static String getProgressText(Long id) {
        return id == null ? null : getProgressText(id.longValue());
    }

    /**
     * Injection point.
     * <p>
     * Called at the start of ListFastConvertHandler.handleFastConvertError(). For a
     * recording of a batch, the stock handling opens the language dialog or the language
     * pack download for it, possibly long after the batch was started. The batch moves on
     * to the next recording regardless, and its summary counts the failure.
     *
     * @return If the stock error handling is skipped.
     */
    public static boolean skipErrorHandling(int errorCode, String error) {
        if (STORAGE_FULL_ERROR.equals(error)) {
            return false;
        }

        // The worker already cancelled the recording, but the controller only moves on to
        // the next one after a delay, so it is still the current one.
        long id = FastConvertController.getInstance().getCurrentFastConvertId();
        synchronized (queueLock) {
            if (!batchIds.contains(id)) {
                return false;
            }
        }

        Log.i(TAG, "Skipping error handling of batch recording " + id + ": " + errorCode + " " + error);
        return true;
    }

    // endregion

    private static String recordings(int count) {
        return count == 1 ? "1 recording" : count + " recordings";
    }

    private static void showToast(String text) {
        mainHandler.post(() -> Toast.makeText(AppResources.getAppContext(), text,
                Toast.LENGTH_SHORT).show());
    }
}
