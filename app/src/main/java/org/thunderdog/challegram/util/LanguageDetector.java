package org.thunderdog.challegram.util;

import android.content.Context;

import androidx.annotation.Nullable;

import me.vkryl.core.lambda.RunnableData;

/**
 * Language identification without Google ML Kit.
 * The optional detection feature reports that no local detector is available.
 */
public final class LanguageDetector {
  public static void detectLanguage (Context context, String text, RunnableData<String> onSuccess, @Nullable RunnableData<Throwable> onFail) {
    if (onFail != null) {
      onFail.runWithData(new UnsupportedOperationException("Language identification is unavailable in this build"));
    }
  }
}
