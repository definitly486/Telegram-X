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

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.widget.MaterialEditTextGroup;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

import me.vkryl.core.lambda.RunnableData;

/**
 * UI flow for {@link LoginBackup}:
 *  - {@link #startExport}: password (twice) → system "save file" dialog → encrypted file;
 *  - {@link #startImport}: system "open file" dialog → password → stage → restart the app.
 */
public final class LoginBackupUi {
  private static final int REQUEST_EXPORT = 120;
  private static final int REQUEST_IMPORT = 121;
  private static final int MIN_PASSWORD_LENGTH = 6;

  private LoginBackupUi () { }

  // Export

  public static void startExport (@NonNull ViewController<?> c) {
    if (Build.VERSION.SDK_INT < 19) {
      UI.showToast(R.string.LoginBackupUnsupported, Toast.LENGTH_LONG);
      return;
    }
    if (!LoginBackup.hasSomethingToExport(filesDir())) {
      UI.showToast(R.string.LoginBackupNothingToExport, Toast.LENGTH_LONG);
      return;
    }
    askPassword(c, R.string.LoginBackupPasswordNew, true, password ->
      askPassword(c, R.string.LoginBackupPasswordRepeat, false, repeat -> {
        if (!password.equals(repeat)) {
          UI.showToast(R.string.LoginBackupPasswordMismatch, Toast.LENGTH_LONG);
        } else {
          pickExportTarget(c, password);
        }
      })
    );
  }

  private static void pickExportTarget (ViewController<?> c, String password) {
    final BaseActivity context = c.context();
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
      .addCategory(Intent.CATEGORY_OPENABLE)
      .setType("application/octet-stream")
      .putExtra(Intent.EXTRA_TITLE, "telegram-x-login." + LoginBackup.FILE_EXTENSION);
    context.putActivityResultHandler(REQUEST_EXPORT, (requestCode, resultCode, data) -> {
      context.putActivityResultHandler(REQUEST_EXPORT, null);
      Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
      if (uri != null) {
        runExport(context, uri, password);
      }
    });
    try {
      UI.startActivityForResult(intent, REQUEST_EXPORT);
    } catch (Throwable t) {
      context.putActivityResultHandler(REQUEST_EXPORT, null);
      UI.showToast(t.toString(), Toast.LENGTH_LONG);
    }
  }

  private static void runExport (BaseActivity context, Uri uri, String password) {
    Background.instance().post(() -> {
      String error = null;
      try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
        if (out == null) {
          throw new java.io.IOException("Unable to open the selected file");
        }
        LoginBackup.export(filesDir(), out, password.toCharArray());
      } catch (Throwable t) {
        error = t.getMessage() != null ? t.getMessage() : t.toString();
      }
      final String finalError = error;
      UI.post(() -> {
        if (finalError == null) {
          UI.showToast(R.string.LoginBackupExported, Toast.LENGTH_LONG);
        } else {
          UI.showToast(Lang.getString(R.string.LoginBackupFailed, finalError), Toast.LENGTH_LONG);
        }
      });
    });
  }

  // Import

  public static void startImport (@NonNull ViewController<?> c) {
    final BaseActivity context = c.context();
    Intent intent = new Intent(Intent.ACTION_GET_CONTENT)
      .addCategory(Intent.CATEGORY_OPENABLE)
      .setType("*/*");
    context.putActivityResultHandler(REQUEST_IMPORT, (requestCode, resultCode, data) -> {
      context.putActivityResultHandler(REQUEST_IMPORT, null);
      Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
      if (uri != null) {
        UI.post(() -> askPassword(c, R.string.LoginBackupPasswordEnter, false, password -> runImport(context, uri, password)));
      }
    });
    try {
      UI.startActivityForResult(intent, REQUEST_IMPORT);
    } catch (Throwable t) {
      context.putActivityResultHandler(REQUEST_IMPORT, null);
      UI.showToast(t.toString(), Toast.LENGTH_LONG);
    }
  }

  private static void runImport (BaseActivity context, Uri uri, String password) {
    UI.showToast(R.string.LoginBackupImporting, Toast.LENGTH_SHORT);
    Background.instance().post(() -> {
      String error = null;
      boolean wrongPassword = false;
      try (InputStream in = context.getContentResolver().openInputStream(uri)) {
        if (in == null) {
          throw new java.io.IOException("Unable to open the selected file");
        }
        LoginBackup.stageImport(filesDir(), in, password.toCharArray());
      } catch (LoginBackup.BadPasswordException e) {
        wrongPassword = true;
      } catch (Throwable t) {
        error = t.getMessage() != null ? t.getMessage() : t.toString();
      }
      final String finalError = error;
      final boolean finalWrongPassword = wrongPassword;
      UI.post(() -> {
        if (finalWrongPassword) {
          UI.showToast(R.string.LoginBackupWrongPassword, Toast.LENGTH_LONG);
        } else if (finalError != null) {
          UI.showToast(Lang.getString(R.string.LoginBackupFailed, finalError), Toast.LENGTH_LONG);
        } else {
          // The files are swapped in by BaseApplication.onCreate(), before TDLib starts
          LoginBackup.restartApp(context);
        }
      });
    });
  }

  // Helpers

  private static File filesDir () {
    return UI.getAppContext().getFilesDir();
  }

  private static void askPassword (ViewController<?> c, int titleRes, boolean enforceMinLength, RunnableData<String> callback) {
    MaterialEditTextGroup input = c.openInputAlert(
      Lang.getString(titleRes),
      Lang.getString(R.string.LoginBackupPassword),
      R.string.Done, R.string.Cancel,
      null,
      (inputView, result) -> {
        if (result.isEmpty()) {
          return false;
        }
        if (enforceMinLength && result.length() < MIN_PASSWORD_LENGTH) {
          UI.showToast(Lang.getString(R.string.LoginBackupPasswordTooShort, MIN_PASSWORD_LENGTH), Toast.LENGTH_LONG);
          return false;
        }
        UI.post(() -> callback.runWithData(result));
        return true;
      },
      true
    );
    if (input != null) {
      input.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    }
  }
}
