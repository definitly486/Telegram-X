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
package org.thunderdog.challegram.unsorted;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted export / import of passcode settings.
 * File format: JSON { "magic", "v", "salt", "iv", "data" },
 * where data = AES-256-GCM(PBKDF2(password), passcode state JSON).
 */
public final class PasscodeBackup {
  public static final String FILE_EXTENSION = "tgxpc";
  public static final String MIME_TYPE = "application/octet-stream";
  public static final int MIN_BACKUP_PASSWORD_SIZE = 4;

  private static final String MAGIC = "tgx-passcode";
  private static final int VERSION = 1;
  private static final int PBKDF2_ITERATIONS = 120_000;
  private static final int KEY_BITS = 256;
  private static final int SALT_BYTES = 16;
  private static final int IV_BYTES = 12;
  private static final int GCM_TAG_BITS = 128;

  private PasscodeBackup () { }

  public static byte[] export (Passcode passcode, String backupPassword) throws GeneralSecurityException, JSONException {
    byte[] plain = passcode.exportState().toString().getBytes(StandardCharsets.UTF_8);
    SecureRandom random = new SecureRandom();
    byte[] salt = new byte[SALT_BYTES];
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(salt);
    random.nextBytes(iv);

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, deriveKey(backupPassword, salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
    byte[] encrypted = cipher.doFinal(plain);

    JSONObject o = new JSONObject();
    o.put("magic", MAGIC);
    o.put("v", VERSION);
    o.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
    o.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
    o.put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
    return o.toString().getBytes(StandardCharsets.UTF_8);
  }

  public enum Result {
    OK, INVALID_FILE, WRONG_PASSWORD
  }

  public static Result importFrom (Passcode passcode, byte[] file, String backupPassword) {
    byte[] salt, iv, data;
    try {
      JSONObject o = new JSONObject(new String(file, StandardCharsets.UTF_8));
      if (!MAGIC.equals(o.optString("magic")) || o.optInt("v") != VERSION)
        return Result.INVALID_FILE;
      salt = Base64.decode(o.getString("salt"), Base64.NO_WRAP);
      iv = Base64.decode(o.getString("iv"), Base64.NO_WRAP);
      data = Base64.decode(o.getString("data"), Base64.NO_WRAP);
    } catch (JSONException | IllegalArgumentException e) {
      return Result.INVALID_FILE;
    }
    byte[] plain;
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, deriveKey(backupPassword, salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
      plain = cipher.doFinal(data);
    } catch (GeneralSecurityException e) {
      return Result.WRONG_PASSWORD; // GCM tag mismatch
    }
    try {
      return passcode.importState(new JSONObject(new String(plain, StandardCharsets.UTF_8))) ? Result.OK : Result.INVALID_FILE;
    } catch (JSONException e) {
      return Result.INVALID_FILE;
    }
  }

  private static SecretKey deriveKey (String password, byte[] salt) throws GeneralSecurityException {
    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
    byte[] key = factory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)).getEncoded();
    return new SecretKeySpec(key, "AES");
  }
}
