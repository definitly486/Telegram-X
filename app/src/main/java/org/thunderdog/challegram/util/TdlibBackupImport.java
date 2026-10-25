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

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.Toast;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * Shared password-protected restore flow usable before a Telegram account is logged in.
 * The validated snapshot is applied by BaseApplication on the next process start.
 */
public final class TdlibBackupImport {
  private TdlibBackupImport() { }

  public static void requestPasswordAndImport(Context context, Uri source) {
    EditText passwordInput = new EditText(context);
    passwordInput.setSingleLine(true);
    passwordInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    passwordInput.setHint(R.string.BackupPasswordHint);
    new AlertDialog.Builder(context)
        .setTitle(R.string.ImportBackup)
        .setView(passwordInput)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
          char[] password = passwordInput.getText().toString().toCharArray();
          if (password.length < 8) {
            Toast.makeText(context, R.string.BackupPasswordHint, Toast.LENGTH_LONG).show();
            Arrays.fill(password, '\0');
            return;
          }
          runImport(context.getApplicationContext(), source, password);
        })
        .show();
  }

  private static void runImport(Context context, Uri source, char[] password) {
    ProgressDialog progress = new ProgressDialog(context);
    progress.setIndeterminate(true);
    progress.setCancelable(false);
    progress.setMessage(Lang.getString(R.string.LoadingInformation));
    progress.show();
    new Thread(() -> {
      File staging = new File(context.getFilesDir().getParentFile(), ".tgx-backup-import-staging");
      try {
        deleteTree(staging);
        TdlibBackupSnapshot.SnapshotRoots roots =
            TdlibBackupSnapshot.validateArchive(context, source, staging, password);
        if (!roots.hasAccountConfig) {
          throw new IOException(Lang.getString(R.string.BackupWrongStructure));
        }
        TdlibBackupSnapshot.markPendingRestore(context, staging);
        new Handler(Looper.getMainLooper()).post(() -> {
          if (progress.isShowing()) progress.dismiss();
          Toast.makeText(context, R.string.BackupImportPrepared, Toast.LENGTH_LONG).show();
          // The pending snapshot is installed only before application initialization.
          new Handler(Looper.getMainLooper()).postDelayed(
              () -> android.os.Process.killProcess(android.os.Process.myPid()), 1800);
        });
      } catch (Throwable error) {
        android.util.Log.e("TGXBackup", "Pre-login backup import failed", error);
        String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        new Handler(Looper.getMainLooper()).post(() -> {
          if (progress.isShowing()) progress.dismiss();
          Toast.makeText(context, context.getString(R.string.BackupFailed, message), Toast.LENGTH_LONG).show();
        });
        deleteTree(staging);
      } finally {
        Arrays.fill(password, '\0');
      }
    }, "TGX-Backup-Import").start();
  }

  private static void deleteTree(File file) {
    if (file == null || !file.exists()) return;
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) deleteTree(child);
    }
    if (!file.delete() && file.exists()) {
      android.util.Log.w("TGXBackup", "Unable to delete temporary backup path: " + file);
    }
  }
}
