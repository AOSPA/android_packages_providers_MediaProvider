/*
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

package com.android.providers.media.util;

import static android.Manifest.permission.BACKUP;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OPSTR_LEGACY_STORAGE;
import static android.app.AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_AUDIO;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_IMAGES;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_VIDEO;
import static android.app.AppOpsManager.OPSTR_WRITE_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_AUDIO;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_IMAGES;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_VIDEO;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.Context;
import android.provider.MediaStore;

public class PermissionUtils {
    // Callers must hold both the old and new permissions, so that we can
    // handle obscure cases like when an app targets Q but was installed on
    // a device that was originally running on P before being upgraded to Q.

    private static volatile int sLegacyMediaProviderUid = -1;

    public static boolean checkPermissionSystem(Context context,
            int pid, int uid, String packageName) {
        // Apps sharing legacy MediaProvider's uid like DownloadProvider and MTP are treated as
        // system.
        return uid == android.os.Process.SYSTEM_UID || uid == android.os.Process.myUid()
                || uid == android.os.Process.SHELL_UID || uid == android.os.Process.ROOT_UID
                || isLegacyMediaProvider(context, uid);
    }

    public static boolean checkPermissionBackup(Context context, int pid, int uid) {
        return context.checkPermission(BACKUP, pid, uid) == PERMISSION_GRANTED;
    }

    public static boolean checkPermissionManageExternalStorage(Context context, int pid, int uid,
            String packageName) {
        return hasAppOpPermission(context, pid, uid, packageName, OPSTR_MANAGE_EXTERNAL_STORAGE);
    }

    public static boolean checkPermissionWriteStorage(Context context,
            int pid, int uid, String packageName) {
        return checkPermissionAndAppOp(context, pid,
                uid, packageName, WRITE_EXTERNAL_STORAGE, OPSTR_WRITE_EXTERNAL_STORAGE);
    }

    public static boolean checkPermissionReadStorage(Context context,
            int pid, int uid, String packageName) {
        return checkPermissionAndAppOp(context, pid,
                uid, packageName, READ_EXTERNAL_STORAGE, OPSTR_READ_EXTERNAL_STORAGE);
    }

    public static boolean checkIsLegacyStorageGranted(Context context, int uid,
            String packageName) {
        return context.getSystemService(AppOpsManager.class)
                .unsafeCheckOp(OPSTR_LEGACY_STORAGE, uid, packageName) == MODE_ALLOWED;
    }

    public static boolean checkPermissionReadAudio(Context context,
            int pid, int uid, String packageName) {
        if (!checkPermissionAndAppOp(context, pid, uid, packageName,
                READ_EXTERNAL_STORAGE, OPSTR_READ_EXTERNAL_STORAGE)) return false;
        return noteAppOpAllowingLegacy(context, pid, uid, packageName,
                OPSTR_READ_MEDIA_AUDIO);
    }

    public static boolean checkPermissionWriteAudio(Context context,
            int pid, int uid, String packageName) {
        if (!checkPermissionAndAppOpAllowingNonLegacy(context, pid, uid, packageName,
                WRITE_EXTERNAL_STORAGE, OPSTR_WRITE_EXTERNAL_STORAGE)) return false;
        return noteAppOpAllowingLegacy(context, pid, uid, packageName,
                OPSTR_WRITE_MEDIA_AUDIO);
    }

    public static boolean checkPermissionReadVideo(Context context,
            int pid, int uid, String packageName) {
        if (!checkPermissionAndAppOp(context, pid, uid, packageName,
                READ_EXTERNAL_STORAGE, OPSTR_READ_EXTERNAL_STORAGE)) return false;
        return noteAppOpAllowingLegacy(context, pid, uid, packageName,
                OPSTR_READ_MEDIA_VIDEO);
    }

    public static boolean checkPermissionWriteVideo(Context context,
            int pid, int uid, String packageName) {
        if (!checkPermissionAndAppOpAllowingNonLegacy(context, pid, uid, packageName,
                WRITE_EXTERNAL_STORAGE, OPSTR_WRITE_EXTERNAL_STORAGE)) return false;
        return noteAppOpAllowingLegacy(context, pid, uid, packageName,
                OPSTR_WRITE_MEDIA_VIDEO);
    }

    public static boolean checkPermissionReadImages(Context context,
            int pid, int uid, String packageName) {
        if (!checkPermissionAndAppOp(context, pid, uid, packageName,
                READ_EXTERNAL_STORAGE, OPSTR_READ_EXTERNAL_STORAGE)) return false;
        return noteAppOpAllowingLegacy(context, pid, uid, packageName,
                OPSTR_READ_MEDIA_IMAGES);
    }

    public static boolean checkPermissionWriteImages(Context context,
            int pid, int uid, String packageName) {
        if (!checkPermissionAndAppOpAllowingNonLegacy(context, pid, uid, packageName,
                WRITE_EXTERNAL_STORAGE, OPSTR_WRITE_EXTERNAL_STORAGE)) return false;
        return noteAppOpAllowingLegacy(context, pid, uid, packageName,
                OPSTR_WRITE_MEDIA_IMAGES);
    }

    private static boolean checkPermissionAndAppOp(Context context,
            int pid, int uid, String packageName, String permission, String op) {
        if (context.checkPermission(permission, pid, uid) != PERMISSION_GRANTED) {
            return false;
        }

        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        try {
            appOps.checkPackage(uid, packageName);
        } catch (SecurityException e) {
            return false;
        }

        final int mode = appOps.unsafeCheckOpNoThrow(op, uid, packageName);
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return true;
            case AppOpsManager.MODE_DEFAULT:
            case AppOpsManager.MODE_IGNORED:
            case AppOpsManager.MODE_ERRORED:
                return false;
            default:
                throw new IllegalStateException(op + " has unknown mode " + mode);
        }
    }

    /**
     * Checks if the given package has the given {@code permission} and {@code op}, but allows it
     * to bypass the permission and app-op check if it's NOT a legacy app, i.e. doesn't hold
     * {@link AppOpsManager#OPSTR_LEGACY_STORAGE}. This is useful for deprecated permissions and/or
     * app-ops, like {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE}
     * @see #checkPermissionAndAppOp
     */
    private static boolean checkPermissionAndAppOpAllowingNonLegacy(Context context,
            int pid, int uid, String packageName, String permission, String op) {
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        try {
            appOps.checkPackage(uid, packageName);
        } catch (SecurityException e) {
            return false;
        }
        // Allowing non legacy apps to bypass this check
        if (appOps.unsafeCheckOpNoThrow(OPSTR_LEGACY_STORAGE, uid,
                packageName) != AppOpsManager.MODE_ALLOWED) return true;

        // Seems like it's a legacy app, so it has to pass the permission and app-op check
        return checkPermissionAndAppOp(context, pid, uid, packageName, permission, op);
    }

    /**
     * Checks if calling app is allowed the app-op. If its app-op mode is
     * {@link AppOpsManager#MODE_DEFAULT} then it falls back to checking the appropriate permission
     * for the app-op. The permission is retrieved from
     * {@link AppOpsManager#opToPermission(String)}.
     */
    private static boolean hasAppOpPermission(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @NonNull String op) {
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        final int mode = appOps.noteOpNoThrow(op, uid, packageName, null, null);
        if (mode == AppOpsManager.MODE_DEFAULT) {
            final String permission = AppOpsManager.opToPermission(op);
            return permission != null
                    && context.checkPermission(permission, pid, uid) == PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private static boolean noteAppOpAllowingLegacy(Context context,
            int pid, int uid, String packageName, String op) {
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        final int mode = appOps.noteOpNoThrow(op, uid, packageName);
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return true;
            case AppOpsManager.MODE_DEFAULT:
            case AppOpsManager.MODE_IGNORED:
            case AppOpsManager.MODE_ERRORED:
                // Legacy apps technically have the access granted by this op,
                // even when the op is denied
                if ((appOps.unsafeCheckOpNoThrow(OPSTR_LEGACY_STORAGE, uid,
                        packageName) == AppOpsManager.MODE_ALLOWED)) return true;

                return false;
            default:
                throw new IllegalStateException(op + " has unknown mode " + mode);
        }
    }

    private static boolean isLegacyMediaProvider(Context context, int uid) {
        if (sLegacyMediaProviderUid == -1) {
            // Uid stays constant while legacy Media Provider stays installed. Cache legacy
            // MediaProvider's uid for the first time.
            sLegacyMediaProviderUid = context.getPackageManager()
                    .resolveContentProvider(MediaStore.AUTHORITY_LEGACY, 0)
                    .applicationInfo.uid;
        }
        return (uid == sLegacyMediaProviderUid);
    }
}
