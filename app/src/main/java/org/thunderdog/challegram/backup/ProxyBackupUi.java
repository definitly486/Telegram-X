/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.backup;

import android.app.Activity;
import android.os.Build;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.widget.MaterialEditTextGroup;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import me.vkryl.core.lambda.RunnableData;

/** File-picker and password UI for encrypted proxy backups. */
public final class ProxyBackupUi {
  private static final int REQUEST_EXPORT = 124;
  private static final int REQUEST_IMPORT = 125;
  private static final int MIN_PASSWORD_LENGTH = 8;

  private ProxyBackupUi() { }

  public static void startExport(@NonNull ViewController<?> controller) {
    if (Build.VERSION.SDK_INT < 19) {
      UI.showToast("Encrypted proxy backups require Android 4.4 or newer", Toast.LENGTH_LONG);
      return;
    }
    List<Settings.Proxy> proxies = Settings.instance().getAvailableProxies();
    if (proxies.isEmpty()) {
      UI.showToast("No proxy configurations to export", Toast.LENGTH_LONG);
      return;
    }
    askPassword(controller, "New backup password", true, password ->
      askPassword(controller, "Repeat backup password", false, repeat -> {
        if (!password.equals(repeat)) {
          UI.showToast("Passwords do not match", Toast.LENGTH_LONG);
        } else {
          pickExportTarget(controller, proxies, password);
        }
      })
    );
  }

  private static void pickExportTarget(ViewController<?> controller, List<Settings.Proxy> proxies, String password) {
    BaseActivity context = controller.context();
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
      .addCategory(Intent.CATEGORY_OPENABLE)
      .setType("application/octet-stream")
      .putExtra(Intent.EXTRA_TITLE, "telegram-x-proxies." + ProxyBackup.FILE_EXTENSION);
    context.putActivityResultHandler(REQUEST_EXPORT, (requestCode, resultCode, data) -> {
      context.putActivityResultHandler(REQUEST_EXPORT, null);
      Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
      if (uri != null) runExport(context, uri, proxies, password);
    });
    try {
      UI.startActivityForResult(intent, REQUEST_EXPORT);
    } catch (Throwable t) {
      context.putActivityResultHandler(REQUEST_EXPORT, null);
      UI.showToast(t.toString(), Toast.LENGTH_LONG);
    }
  }

  private static void runExport(BaseActivity context, Uri uri, List<Settings.Proxy> proxies, String password) {
    Background.instance().post(() -> {
      String error = null;
      try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
        if (out == null) throw new java.io.IOException("Unable to open the selected file");
        ProxyBackup.export(proxies, out, password.toCharArray());
      } catch (Throwable t) {
        error = t.getMessage() != null ? t.getMessage() : t.toString();
      }
      final String result = error;
      UI.post(() -> UI.showToast(result == null ? "Encrypted proxy backup exported" : "Export failed: " + result, Toast.LENGTH_LONG));
    });
  }

  public static void startImport(@NonNull ViewController<?> controller) {
    if (Build.VERSION.SDK_INT < 19) {
      UI.showToast("Encrypted proxy backups require Android 4.4 or newer", Toast.LENGTH_LONG);
      return;
    }
    BaseActivity context = controller.context();
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
      .addCategory(Intent.CATEGORY_OPENABLE)
      .setType("*/*");
    context.putActivityResultHandler(REQUEST_IMPORT, (requestCode, resultCode, data) -> {
      context.putActivityResultHandler(REQUEST_IMPORT, null);
      Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
      if (uri != null) UI.post(() -> askPassword(controller, "Backup password", false, password -> runImport(context, uri, password)));
    });
    try {
      UI.startActivityForResult(intent, REQUEST_IMPORT);
    } catch (Throwable t) {
      context.putActivityResultHandler(REQUEST_IMPORT, null);
      UI.showToast(t.toString(), Toast.LENGTH_LONG);
    }
  }

  private static void runImport(BaseActivity context, Uri uri, String password) {
    UI.showToast("Importing proxy settings…", Toast.LENGTH_SHORT);
    Background.instance().post(() -> {
      List<ProxyBackup.ImportedProxy> imported = null;
      String error = null;
      boolean wrongPassword = false;
      try (InputStream in = context.getContentResolver().openInputStream(uri)) {
        if (in == null) throw new java.io.IOException("Unable to open the selected file");
        imported = ProxyBackup.importProxies(in, password.toCharArray());
      } catch (ProxyBackup.BadPasswordException e) {
        wrongPassword = true;
      } catch (Throwable t) {
        error = t.getMessage() != null ? t.getMessage() : t.toString();
      }
      final List<ProxyBackup.ImportedProxy> result = imported;
      final String finalError = error;
      final boolean finalWrongPassword = wrongPassword;
      UI.post(() -> {
        if (finalWrongPassword) {
          UI.showToast("Wrong password or corrupted backup", Toast.LENGTH_LONG);
        } else if (finalError != null) {
          UI.showToast("Import failed: " + finalError, Toast.LENGTH_LONG);
        } else {
          int count = 0;
          for (ProxyBackup.ImportedProxy item : result) {
            Settings.instance().addOrUpdateProxy(item.proxy, item.description, false);
            count++;
          }
          UI.showToast("Imported " + count + " proxy settings", Toast.LENGTH_LONG);
        }
      });
    });
  }

  private static void askPassword(ViewController<?> controller, String title, boolean enforceMinLength, RunnableData<String> callback) {
    MaterialEditTextGroup input = controller.openInputAlert(
      title, "Backup password", R.string.Done, R.string.Cancel, null,
      (inputView, result) -> {
        if (result.isEmpty()) return false;
        if (enforceMinLength && result.length() < MIN_PASSWORD_LENGTH) {
          UI.showToast("Use at least " + MIN_PASSWORD_LENGTH + " characters", Toast.LENGTH_LONG);
          return false;
        }
        UI.post(() -> callback.runWithData(result));
        return true;
      }, true
    );
    if (input != null) input.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
  }
}
