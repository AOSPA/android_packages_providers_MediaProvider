/**
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tests.fused.lib;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.android.tests.fused.lib.ReaddirTestHelper.READDIR_QUERY;
import static com.android.tests.fused.lib.RedactionTestHelper.EXIF_METADATA_QUERY;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.TestApp;
import com.android.cts.install.lib.Uninstall;

import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * General helper functions for FuseDaemon tests.
 */
public class TestUtils {
    static final String TAG = "FuseDaemonTest";

    public static final String QUERY_TYPE = "com.android.tests.fused.queryType";
    public static final String INTENT_EXTRA_PATH = "com.android.tests.fused.path";
    public static final String INTENT_EXCEPTION = "com.android.tests.fused.exception";
    public static final String CREATE_FILE_QUERY = "com.android.tests.fused.createfile";
    public static final String DELETE_FILE_QUERY = "com.android.tests.fused.deletefile";

    private static final long POLLING_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);
    private static final long POLLING_SLEEP_MILLIS = 100;

    private static final UiAutomation sUiAutomation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation();

    /**
     * Grants {@link Manifest.permission#GRANT_RUNTIME_PERMISSIONS} to the given package.
     */
    public static void grantReadExternalStorage(String packageName) {
        sUiAutomation.adoptShellPermissionIdentity("android.permission.GRANT_RUNTIME_PERMISSIONS");
        try {
            sUiAutomation.grantRuntimePermission(packageName,
                    Manifest.permission.READ_EXTERNAL_STORAGE);
            // Wait for OP_READ_EXTERNAL_STORAGE to get updated.
            SystemClock.sleep(1000);
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * Revokes {@link Manifest.permission#GRANT_RUNTIME_PERMISSIONS} from the given package.
     */
    public static void revokeReadExternalStorage(String packageName) {
        sUiAutomation.adoptShellPermissionIdentity("android.permission.REVOKE_RUNTIME_PERMISSIONS");
        try {
            sUiAutomation.revokeRuntimePermission(packageName,
                    Manifest.permission.READ_EXTERNAL_STORAGE);
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    public static void adoptShellPermissionIdentity(String... permissions) {
        sUiAutomation.adoptShellPermissionIdentity(permissions);
    }

    public static void dropShellPermissionIdentity() {
        sUiAutomation.dropShellPermissionIdentity();
    }

    public static String executeShellCommand(String cmd) throws Exception {
        try (FileInputStream output = new FileInputStream (sUiAutomation.executeShellCommand(cmd)
                .getFileDescriptor())) {
            return new String(ByteStreams.toByteArray(output));
        }
    }

    /**
     * Makes the given {@code testApp} list the content of the given directory and returns the
     * result as an {@link ArrayList}
     */
    public static ArrayList<String> listAs(TestApp testApp, String dirPath)
            throws Exception {
        return getContentsFromTestApp(testApp, dirPath, READDIR_QUERY);
    }

    /**
     * Makes the given {@code testApp} read the EXIF metadata from the given file and returns the
     * result as an {@link HashMap}
     */
    public static HashMap<String, String> readExifMetadataFromTestApp(TestApp testApp,
            String filePath) throws Exception {
        HashMap<String, String> res =
                getMetadataFromTestApp(testApp, filePath, EXIF_METADATA_QUERY);
        if (res.containsKey(INTENT_EXCEPTION)) {
            throw new IllegalStateException(res.get(INTENT_EXCEPTION));
        }
        return res;
    }

    /**
     * Makes the given {@code testApp} create a file.
     *
     * <p>This method drops shell permission identity.
     */
    public static boolean createFileAs(TestApp testApp, String path) throws Exception {
        return createOrDeleteFileFromTestApp(testApp, path, CREATE_FILE_QUERY);
    }

    /**
     * Makes the given {@code testApp} delete a file.
     *
     * <p>This method drops shell permission identity.
     */
    public static boolean deleteFileAs(TestApp testApp, String path) throws Exception {
        return createOrDeleteFileFromTestApp(testApp, path, DELETE_FILE_QUERY);
    }

    /**
     * Makes the given {@code testApp} delete a file. Doesn't throw in case of failure.
     */
    public static boolean deleteFileAsNoThrow(TestApp testApp, String path) {
        try {
            return deleteFileAs(testApp, path);
        } catch (Exception e) {
            Log.e(TAG, "Error occurred while deleting file: " + path
                    + " on behalf of app: " + testApp, e);
            return false;
        }
    }

    /**
     * Installs a {@link TestApp} and may grant it storage permissions.
     */
    public static void installApp(TestApp testApp, boolean grantStoragePermission)
            throws Exception {

        try {
            final String packageName = testApp.getPackageName();
            sUiAutomation.adoptShellPermissionIdentity(Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES);
            if (InstallUtils.getInstalledVersion(packageName) != -1) {
                Uninstall.packages(packageName);
            }
            Install.single(testApp).commit();
            assertThat(InstallUtils.getInstalledVersion(packageName)).isEqualTo(1);
            if (grantStoragePermission) {
                grantReadExternalStorage(packageName);
            }
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * Uninstalls a {@link TestApp}.
     */
    public static void uninstallApp(TestApp testApp) throws Exception {
        try {
            final String packageName = testApp.getPackageName();
            sUiAutomation.adoptShellPermissionIdentity(Manifest.permission.DELETE_PACKAGES);

            Uninstall.packages(packageName);
            assertThat(InstallUtils.getInstalledVersion(packageName)).isEqualTo(-1);
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * Uninstalls a {@link TestApp}. Doesn't throw in case of failure.
     */
    public static void uninstallAppNoThrow(TestApp testApp) {
        try {
            uninstallApp(testApp);
        } catch (Exception e) {
            Log.e(TAG, "Exception occurred while uninstalling app: " + testApp, e);
        }
    }

    public static ContentResolver getContentResolver() {
        return getContext().getContentResolver();
    }

    /**
     * Queries {@link ContentResolver} for a file and returns the corresponding {@link Uri} for its
     * entry in the database. Returns {@code null} if file doesn't exist in the database.
     */
    @Nullable
    public static Uri getFileUri(@NonNull File file) {
        final Uri contentUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        final int id = getFileRowIdFromDatabase(file);
        return id == -1 ? null : ContentUris.withAppendedId(contentUri, id);
    }

    /**
     * Queries {@link ContentResolver} for a file and returns the corresponding row ID for its
     * entry in the database.
     */
    public static int getFileRowIdFromDatabase(@NonNull File file) {
        int id  = -1;
        try (Cursor c = queryFile(file, MediaStore.MediaColumns._ID)) {
            if (c.moveToFirst()) {
                id = c.getInt(0);
            }
        }
        return id;
    }

    /**
     * Queries {@link ContentResolver} for a file and returns the corresponding owner package name
     * for its entry in the database.
     */
    @Nullable
    public static String getFileOwnerPackageFromDatabase(@NonNull File file) {
        String ownerPackage = null;
        try (Cursor c = queryFile(file, MediaStore.MediaColumns.OWNER_PACKAGE_NAME)) {
            if (c.moveToFirst()) {
                ownerPackage = c.getString(0);
            }
        }
        return ownerPackage;
    }

    /**
     * Queries {@link ContentResolver} for a file and returns the corresponding mime type for its
     * entry in the database.
     */
    @NonNull
    public static String getFileMimeTypeFromDatabase(@NonNull File file) {
        String mimeType = "";
        try (Cursor c = queryFile(file, MediaStore.MediaColumns.MIME_TYPE)) {
            if(c.moveToFirst()) {
                mimeType = c.getString(0);
            }
        }
        return mimeType;
    }

    /**
     * Sets {@link AppOpsManager#MODE_ALLOWED} for the given {@code ops} and the given {@code uid}.
     *
     * <p>This method drops shell permission identity.
     */
    public static void allowAppOpsToUid(int uid, @NonNull String... ops) {
        setAppOpsModeForUid(uid, AppOpsManager.MODE_ALLOWED, ops);
    }

    /**
     * Sets {@link AppOpsManager#MODE_ERRORED} for the given {@code ops} and the given {@code uid}.
     *
     * <p>This method drops shell permission identity.
     */
    public static void denyAppOpsToUid(int uid, @NonNull String... ops) {
        setAppOpsModeForUid(uid, AppOpsManager.MODE_ERRORED, ops);
    }

    /**
     * Deletes the given file through {@link ContentResolver} and {@link MediaStore} APIs,
     * and asserts that the file was successfully deleted from the database.
     */
    public static void deleteWithMediaProvider(@NonNull File file) {
        assertThat(getContentResolver().delete(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                /*where*/MediaStore.MediaColumns.DATA + " = ?",
                /*selectionArgs*/new String[] { file.getPath() }))
                .isEqualTo(1);
    }

    /**
     * Renames the given file through {@link ContentResolver} and {@link MediaStore} APIs,
     * and asserts that the file was updated in the database.
     */
    public static void updateDisplayNameWithMediaProvider(String relativePath,
            String oldDisplayName, String newDisplayName) {
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " = ? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + " = ?";
        String[] selectionArgs = { relativePath + '/', oldDisplayName };
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA};

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName);

        try (final Cursor cursor = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs,
                null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
            cursor.moveToFirst();
            int id = cursor.getInt(cursor.getColumnIndex(MediaStore.MediaColumns._ID));
            String data = cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns.DATA));
            Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            Log.i(TAG, "Uri: " + uri + ". Data: " + data);
            assertThat(getContentResolver().update(uri, values, selection, selectionArgs))
                    .isEqualTo(1);
        }
    }

    /**
     * Opens the given file through {@link ContentResolver} and {@link MediaStore} APIs.
     */
    @NonNull
    public static ParcelFileDescriptor openWithMediaProvider(@NonNull File file, String mode)
            throws Exception {
        final Uri fileUri = getFileUri(file);
        assertThat(fileUri).isNotNull();
        Log.i(TAG, "Uri: " + fileUri + ". Data: " + file.getPath());
        ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(fileUri, mode);
        assertThat(pfd).isNotNull();
        return pfd;
    }

    public static <T extends Exception> void assertThrows(Class<T> clazz, Operation<T> r)
            throws Exception {
        assertThrows(clazz, "", r);
    }

    public static <T extends Exception> void assertThrows(Class<T> clazz, String errMsg,
            Operation<T> r) throws Exception {
        try {
            r.run();
            fail("Expected " + clazz + " to be thrown");
        } catch (Exception e) {
            if (!clazz.isAssignableFrom(e.getClass()) || !e.getMessage().contains(errMsg)) {
                Log.e(TAG, "Expected " + clazz + " exception with error message: " + errMsg, e);
                throw e;
            }
        }
    }

    /**
     * A functional interface representing an operation that takes no arguments,
     * returns no arguments and might throw an {@link Exception} of any kind.
     */
    @FunctionalInterface
    public interface Operation<T extends Exception> {
        /**
         * This is the method that gets called for any object that implements this interface.
         */
        void run() throws T;
    }

    /**
     * Deletes the given file. If the file is a directory, then deletes all of it's children (files
     * or directories) recursively.
     */
    public static boolean deleteRecursively(@NonNull File path) {
        if (path.isDirectory()) {
            for (File child : path.listFiles()) {
                if (!deleteRecursively(child)) {
                    return false;
                }
            }
        }
        return path.delete();
    }

    public static void pollForExternalStorageState() throws Exception {
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
            if(Environment.getExternalStorageState(Environment.getExternalStorageDirectory())
                    .equals(Environment.MEDIA_MOUNTED)) {
                return;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        fail("Timed out while waiting for ExternalStorageState to be MEDIA_MOUNTED");
    }

    public static void pollForPermission(String perm, boolean granted) throws Exception {
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
            if (granted == checkPermissionAndAppOp(perm)) {
                return;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        fail("Timed out while waiting for permission " + perm + " to be "
                + (granted ? "granted" : "revoked"));
    }

    /**
     * Checks if the given {@code permission} is granted and corresponding AppOp is MODE_ALLOWED.
     */
    private static boolean checkPermissionAndAppOp(String permission) {
        final int pid  = Os.getpid();
        final int uid = Os.getuid();
        final Context context = getContext();
        final String packageName = context.getPackageName();
        if (context.checkPermission(permission, pid, uid) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        final String op = AppOpsManager.permissionToOp(permission);
        // No AppOp associated with the given permission, skip AppOp check.
        if (op == null) return true;

        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        try {
            appOps.checkPackage(uid, packageName);
        } catch (SecurityException e) {
            return false;
        }

        return appOps.unsafeCheckOpNoThrow(op, uid, packageName) == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * <p>This method drops shell permission identity.
     */
    private static void forceStopApp(String packageName) throws Exception {
        try {
            sUiAutomation.adoptShellPermissionIdentity(Manifest.permission.FORCE_STOP_PACKAGES);

            getContext().getSystemService(ActivityManager.class).forceStopPackage(packageName);
            Thread.sleep(1000);
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * <p>This method drops shell permission identity.
     */
    private static void sendIntentToTestApp(TestApp testApp, String dirPath, String actionName,
            BroadcastReceiver broadcastReceiver, CountDownLatch latch) throws Exception {

        final String packageName = testApp.getPackageName();
        forceStopApp(packageName);
        // Register broadcast receiver
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(actionName);
        intentFilter.addCategory(Intent.CATEGORY_DEFAULT);
        getContext().registerReceiver(broadcastReceiver, intentFilter);

        // Launch the test app.
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setPackage(packageName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(QUERY_TYPE, actionName);
        intent.putExtra(INTENT_EXTRA_PATH, dirPath);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        getContext().startActivity(intent);
        latch.await();
        getContext().unregisterReceiver(broadcastReceiver);
    }

    /**
     * Gets images/video metadata from a test app.
     *
     * <p>This method drops shell permission identity.
     */
    private static HashMap<String, String> getMetadataFromTestApp(TestApp testApp, String dirPath,
            String actionName) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final HashMap<String, String> appOutputList = new HashMap<>();
        final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.hasExtra(INTENT_EXCEPTION)) {
                    appOutputList.put(INTENT_EXCEPTION,
                            ((Exception)intent.getExtras().get(INTENT_EXCEPTION)).getMessage());
                } else if(intent.hasExtra(actionName)) {
                    HashMap<String, String> res =
                            (HashMap<String, String>) intent.getExtras().get(actionName);
                    appOutputList.putAll(res);
                }
                latch.countDown();
            }
        };
        sendIntentToTestApp(testApp, dirPath, actionName, broadcastReceiver, latch);
        return appOutputList;
    }

    /**
     * <p>This method drops shell permission identity.
     */
    private static ArrayList<String> getContentsFromTestApp(TestApp testApp, String dirPath,
            String actionName) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final ArrayList<String> appOutputList = new ArrayList<String>();
        final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(intent.hasExtra(actionName)) {
                    appOutputList.addAll(intent.getStringArrayListExtra(actionName));
                }
                latch.countDown();
            }
        };

        sendIntentToTestApp(testApp, dirPath, actionName, broadcastReceiver, latch);
        return appOutputList;
    }

    /**
     * <p>This method drops shell permission identity.
     */
    private static boolean createOrDeleteFileFromTestApp(TestApp testApp, String dirPath,
            String actionName) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] appOutput = new boolean[1];
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(intent.hasExtra(actionName)) {
                    appOutput[0] = intent.getBooleanExtra(actionName, false);
                }
                latch.countDown();
            }
        };

        sendIntentToTestApp(testApp, dirPath, actionName, broadcastReceiver, latch);
        return appOutput[0];
    }

    /**
     * Sets {@code mode} for the given {@code ops} and the given {@code uid}.
     *
     * <p>This method drops shell permission identity.
     */
    private static void setAppOpsModeForUid(int uid, int mode, @NonNull String... ops) {
        adoptShellPermissionIdentity(null);
        try {
            for (String op : ops) {
                getContext().getSystemService(AppOpsManager.class)
                        .setUidMode(op, uid, mode);
            }
        } finally {
            dropShellPermissionIdentity();
        }
    }

    @NonNull
    private static Cursor queryFile(@NonNull File file,
            String... projection) {
        final Cursor c = getContentResolver().query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                projection,
                /*selection*/ MediaStore.MediaColumns.DATA + " = ?",
                /*selectionArgs*/ new String[] { file.getAbsolutePath() },
                /*sortOrder*/ null);
        assertThat(c).isNotNull();
        return c;
    }
}