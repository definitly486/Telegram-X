/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.util;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Creates an app-data snapshot for TdlibBackupArchive.
 *
 * IMPORTANT: the caller must prevent app/TDLib writes for the duration of snapshot creation
 * and must only restore into an inactive, freshly staged app-data layout. This class does not
 * stop TDLib and does not install files into live app storage.
 */
public final class TdlibBackupSnapshot {
  private static final String INTERNAL_ROOT = "internal";
  private static final String EXTERNAL_ROOT = "external";
  private static final String[] DATA_DIRS = {"files", "shared_prefs", "databases", "no_backup"};
  public static final String PENDING_DIRECTORY = ".tgx-backup-pending";
  private static final String ROLLBACK_DIRECTORY = ".tgx-backup-rollback";
  private static final String READY_MARKER = "READY";

  private TdlibBackupSnapshot() {}

  /**
   * Copies the app's private data directory and app-specific external files into staging.
   * Cache and code_cache are intentionally omitted; files, databases, shared preferences,
   * TDLib account configuration and account directories are otherwise preserved.
   */
  public static void createSnapshot(@NonNull Context context, @NonNull File staging)
      throws IOException {
    if (staging.exists()) {
      File[] children = staging.listFiles();
      if (children != null && children.length != 0) {
        throw new IOException("Snapshot staging directory must be empty");
      }
    } else if (!staging.mkdirs()) {
      throw new IOException("Cannot create snapshot staging directory");
    }

    File internal = new File(staging, INTERNAL_ROOT);
    if (!internal.mkdirs()) throw new IOException("Cannot create internal snapshot directory");
    // Copy only portable app data. The data root can contain links to APK/native libraries.
    for (String name : DATA_DIRS) {
      File source = new File(context.getDataDir(), name);
      if (source.exists()) copyTree(source, new File(internal, name));
    }

    File externalSource = context.getExternalFilesDir(null);
    if (externalSource != null && externalSource.isDirectory()) {
      File external = new File(staging, EXTERNAL_ROOT);
      if (!external.mkdirs()) throw new IOException("Cannot create external snapshot directory");
      copyTree(externalSource, external);
    }
  }

  /**
   * Decrypt and validate the archive into staging first, then call this method only after the
   * app has been stopped and the current installation has been backed up separately.
   *
   * This method returns the staged roots; it intentionally does not overwrite Android's live
   * application data because doing so in-process is unsafe and unsupported by Android.
   */
  public static SnapshotRoots validateArchive(
      @NonNull Context context,
      @NonNull Uri source,
      @NonNull File emptyStaging,
      @NonNull char[] password
  ) throws Exception {
    TdlibBackupArchive.importToStaging(context, source, emptyStaging, password);
    File internal = new File(emptyStaging, INTERNAL_ROOT);
    if (!internal.isDirectory()) throw new IOException("Backup does not contain internal app data");
    File external = new File(emptyStaging, EXTERNAL_ROOT);
    if (external.exists() && !external.isDirectory()) {
      throw new IOException("Invalid external data directory");
    }
    // Basic structural checks. Do not inspect/copy live TDLib databases while the app is running.
    File accountConfig = new File(new File(internal, "files"), "tdlib_accounts.bin");
    return new SnapshotRoots(internal, external.exists() ? external : null, accountConfig.exists());
  }


  /** Marks a fully decrypted and validated snapshot for installation on the next process start. */
  public static void markPendingRestore(@NonNull Context context, @NonNull File staging)
      throws IOException {
    File root = context.getDataDir();
    File pending = new File(root, PENDING_DIRECTORY);
    if (pending.exists()) deleteTree(pending);
    if (!staging.getCanonicalFile().equals(pending.getCanonicalFile())) {
      if (!staging.renameTo(pending)) {
        copyTree(staging, pending);
        deleteTree(staging);
      }
    }
    File ready = new File(pending, READY_MARKER);
    try (FileOutputStream out = new FileOutputStream(ready)) {
      out.write("TGX-RESTORE-1".getBytes(StandardCharsets.US_ASCII));
      out.getFD().sync();
    }
  }

  /**
   * Installs a previously authenticated snapshot before UI.initApp can initialize TDLib.
   * Existing directories are renamed to a rollback area first and restored if any operation fails.
   */
  public static boolean applyPendingRestore(@NonNull Context context) {
    File root = context.getDataDir();
    File pending = new File(root, PENDING_DIRECTORY);
    File ready = new File(pending, READY_MARKER);
    if (!ready.isFile()) return false;

    File internal = new File(pending, INTERNAL_ROOT);
    File rollback = new File(root, ROLLBACK_DIRECTORY);
    List<String> installed = new ArrayList<>();
    try {
      if (!internal.isDirectory()) throw new IOException("Missing internal snapshot");
      recoverInterruptedRestore(context, rollback);
      if (rollback.exists()) deleteTree(rollback);
      if (!rollback.mkdirs()) throw new IOException("Cannot create rollback directory");

      for (String name : DATA_DIRS) {
        File staged = new File(internal, name);
        if (!staged.exists()) continue;
        File current = new File(root, name);
        File old = new File(rollback, name);
        if (current.exists() && !current.renameTo(old)) {
          throw new IOException("Cannot preserve existing " + name);
        }
        if (!staged.renameTo(current)) {
          if (old.exists()) old.renameTo(current);
          throw new IOException("Cannot install restored " + name);
        }
        installed.add(name);
      }

      File external = new File(pending, EXTERNAL_ROOT);
      if (external.isDirectory()) replaceExternalTree(context, external, rollback);

      ready.delete();
      deleteTree(pending);
      deleteTree(rollback);
      return true;
    } catch (Throwable error) {
      for (int i = installed.size() - 1; i >= 0; i--) {
        String name = installed.get(i);
        File current = new File(root, name);
        File old = new File(rollback, name);
        deleteTree(current);
        if (old.exists()) old.renameTo(current);
      }
      ready.delete();
      android.util.Log.e("TGXBackup", "Unable to apply staged backup; rolled back", error);
      return false;
    }
  }

  private static void recoverInterruptedRestore(Context context, File rollback) {
    if (rollback.exists()) {
      File root = context.getDataDir();
      for (String name : DATA_DIRS) {
        File current = new File(root, name);
        File old = new File(rollback, name);
        if (!current.exists() && old.exists()) old.renameTo(current);
      }
      deleteTree(rollback);
    }
    File target = context.getExternalFilesDir(null);
    if (target != null && target.getParentFile() != null) {
      File parent = target.getParentFile();
      File old = new File(parent, target.getName() + ".tgx-restore-old");
      File temp = new File(parent, target.getName() + ".tgx-restore-new");
      if (!target.exists() && old.exists()) old.renameTo(target);
      deleteTree(old);
      deleteTree(temp);
    }
  }

  private static void replaceExternalTree(Context context, File staged, File rollback)
      throws IOException {
    File target = context.getExternalFilesDir(null);
    if (target == null) throw new IOException("External app storage is unavailable");
    File parent = target.getParentFile();
    if (parent == null) throw new IOException("Invalid external app storage path");
    File temp = new File(parent, target.getName() + ".tgx-restore-new");
    File old = new File(parent, target.getName() + ".tgx-restore-old");
    deleteTree(temp);
    deleteTree(old);
    copyTree(staged, temp);
    if (target.exists() && !target.renameTo(old)) {
      deleteTree(temp);
      throw new IOException("Cannot preserve external TDLib files");
    }
    if (!temp.renameTo(target)) {
      if (old.exists()) old.renameTo(target);
      throw new IOException("Cannot install external TDLib files");
    }
    deleteTree(old);
  }

  private static void deleteTree(File file) {
    if (file == null || !file.exists()) return;
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) deleteTree(child);
    }
    file.delete();
  }

  private static void copyTree(File source, File destination, File... excluded)
      throws IOException {
    if (!source.getCanonicalPath().equals(source.getAbsolutePath())) {
      throw new IOException("Symbolic links are not supported in backup data: " + source);
    }
    if (isExcluded(source, excluded)) return;
    if (source.isDirectory()) {
      if (!destination.exists() && !destination.mkdirs()) {
        throw new IOException("Cannot create directory: " + destination);
      }
      File[] children = source.listFiles();
      if (children == null) throw new IOException("Cannot read directory: " + source);
      List<File> sorted = new ArrayList<>();
      Collections.addAll(sorted, children);
      Collections.sort(sorted, (a, b) -> a.getName().compareTo(b.getName()));
      for (File child : sorted) {
        copyTree(child, new File(destination, child.getName()), excluded);
      }
    } else if (source.isFile()) {
      File parent = destination.getParentFile();
      if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
        throw new IOException("Cannot create parent directory");
      }
      try (InputStream in = new FileInputStream(source);
           OutputStream out = new FileOutputStream(destination)) {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        out.flush();
      }
    }
  }

  private static boolean isExcluded(File file, File[] excluded) throws IOException {
    String path = file.getCanonicalPath();
    for (File item : excluded) {
      if (item != null) {
        String excludedPath = item.getCanonicalPath();
        if (path.equals(excludedPath) || path.startsWith(excludedPath + File.separator)) {
          return true;
        }
      }
    }
    return false;
  }

  public static final class SnapshotRoots {
    public final File internalDirectory;
    public final File externalDirectory;
    public final boolean hasAccountConfig;

    SnapshotRoots(File internalDirectory, File externalDirectory, boolean hasAccountConfig) {
      this.internalDirectory = internalDirectory;
      this.externalDirectory = externalDirectory;
      this.hasAccountConfig = hasAccountConfig;
    }
  }
}
