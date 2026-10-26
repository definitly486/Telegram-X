/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.backup;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;

import java.io.File;

/**
 * Wipes all application data (internal and external storage, databases, preferences, caches)
 * and asks the system to uninstall the application.
 */
public final class SelfDestruct {
  private SelfDestruct () { }

  public static void execute (@NonNull Context context) {
    Context app = context.getApplicationContext();
    wipeData(app);
    boolean uninstallStarted = false;
    try {
      Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.getPackageName()));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      app.startActivity(intent);
      uninstallStarted = true;
    } catch (Throwable ignored) { }
    if (!uninstallStarted) {
      // Could not open the uninstall dialog: at least reset the app to a clean state.
      clearViaSystem(app);
    }
  }

  private static void wipeData (Context context) {
    deleteContents(context.getFilesDir());
    deleteContents(context.getCacheDir());
    deleteContents(context.getCodeCacheDir());
    deleteContents(context.getNoBackupFilesDir());
    File dataDir = Build.VERSION.SDK_INT >= 24 ? context.getDataDir() : (context.getFilesDir() != null ? context.getFilesDir().getParentFile() : null);
    if (dataDir != null) {
      File[] children = dataDir.listFiles();
      if (children != null) {
        for (File child : children) {
          String name = child.getName();
          // Keep only the folders the runtime itself needs to stay functional until the app is removed
          if (!name.equals("lib") && !name.equals("code_cache")) {
            deleteRecursively(child);
          }
        }
      }
    }
    File[] external = context.getExternalFilesDirs(null);
    if (external != null) {
      for (File dir : external) {
        deleteContents(dir);
      }
    }
    File[] externalCache = context.getExternalCacheDirs();
    if (externalCache != null) {
      for (File dir : externalCache) {
        deleteContents(dir);
      }
    }
    File[] obb = context.getObbDirs();
    if (obb != null) {
      for (File dir : obb) {
        deleteContents(dir);
      }
    }
  }

  private static void clearViaSystem (Context context) {
    if (Build.VERSION.SDK_INT >= 19) {
      try {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
          am.clearApplicationUserData();
        }
      } catch (Throwable ignored) { }
    }
  }

  private static void deleteContents (File dir) {
    if (dir == null) return;
    File[] children = dir.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteRecursively(child);
      }
    }
  }

  private static void deleteRecursively (File file) {
    if (file == null) return;
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          deleteRecursively(child);
        }
      }
    }
    //noinspection ResultOfMethodCallIgnored
    file.delete();
  }
}
