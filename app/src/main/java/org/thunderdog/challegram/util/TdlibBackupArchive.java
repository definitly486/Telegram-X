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
package org.thunderdog.challegram.util;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password-protected container for an app-private data snapshot.
 *
 * The archive format is versioned and authenticated (PBKDF2-HMAC-SHA256 + AES-256-GCM).
 * Callers MUST stop TDLib and all writers before creating a snapshot, and MUST NOT
 * overwrite live app data until an archive has been fully decrypted and validated.
 *
 * This class deliberately does not attempt to copy Android Keystore entries: those are
 * non-exportable and cannot be made portable by copying app-private files.
 */
public final class TdlibBackupArchive {
  private static final byte[] MAGIC = new byte[] {'T', 'G', 'X', 'B', 'A', 'K', '1', 0};
  private static final int VERSION = 1;
  private static final int SALT_SIZE = 16;
  private static final int IV_SIZE = 12;
  private static final int KEY_BITS = 256;
  private static final int PBKDF2_ITERATIONS = 310_000;
  private static final int GCM_TAG_BITS = 128;
  private static final int BUFFER_SIZE = 64 * 1024;
  private static final int MAX_ENTRIES = 100_000;
  private static final long MAX_ENTRY_SIZE = 8L * 1024 * 1024 * 1024;

  private TdlibBackupArchive() {}

  /**
   * Encrypts a ZIP snapshot to a SAF URI. Source must be a quiescent staging directory,
   * not the live app data directory. The caller owns staging and its cleanup.
   */
  public static void exportDirectory(
      @NonNull Context context,
      @NonNull File stagingDirectory,
      @NonNull Uri destination,
      @NonNull char[] password
  ) throws IOException, GeneralSecurityException {
    requirePassword(password);
    if (!stagingDirectory.isDirectory()) {
      throw new IOException("Backup staging directory does not exist");
    }

    byte[] salt = randomBytes(SALT_SIZE);
    byte[] iv = randomBytes(IV_SIZE);
    SecretKeySpec key = deriveKey(password, salt);
    Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, iv);

    try (OutputStream raw = context.getContentResolver().openOutputStream(destination, "wt")) {
      if (raw == null) throw new IOException("Cannot open destination");
      try (DataOutputStream header = new DataOutputStream(new BufferedOutputStream(raw))) {
        header.write(MAGIC);
        header.writeInt(VERSION);
        header.writeInt(PBKDF2_ITERATIONS);
        header.writeByte(salt.length);
        header.write(salt);
        header.writeByte(iv.length);
        header.write(iv);
        header.flush();
        try (CipherOutputStream encrypted = new CipherOutputStream(
            new NonClosingOutputStream(header), cipher);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(encrypted))) {
          zipDirectory(stagingDirectory, stagingDirectory, zip);
          zip.finish();
        }
      }
    } finally {
      Arrays.fill(password, '\0');
      Arrays.fill(salt, (byte) 0);
      Arrays.fill(iv, (byte) 0);
    }
  }

  /**
   * Decrypts and validates the complete archive into a new, empty staging directory.
   * This method NEVER merges data into the live application directory.
   */
  public static void importToStaging(
      @NonNull Context context,
      @NonNull Uri source,
      @NonNull File emptyStagingDirectory,
      @NonNull char[] password
  ) throws IOException, GeneralSecurityException {
    requirePassword(password);
    if (emptyStagingDirectory.exists() && emptyStagingDirectory.list() != null
        && emptyStagingDirectory.list().length != 0) {
      throw new IOException("Staging directory must be empty");
    }
    if (!emptyStagingDirectory.isDirectory() && !emptyStagingDirectory.mkdirs()) {
      throw new IOException("Cannot create staging directory");
    }

    File validationDirectory = new File(emptyStagingDirectory, ".tgx-restore-validation");
    if (!validationDirectory.mkdirs()) throw new IOException("Cannot create validation directory");
    boolean success = false;
    byte[] salt = null;
    byte[] iv = null;

    try (InputStream raw = context.getContentResolver().openInputStream(source)) {
      if (raw == null) throw new IOException("Cannot open source");
      DataInputStream header = new DataInputStream(new BufferedInputStream(raw));
      byte[] magic = new byte[MAGIC.length];
      header.readFully(magic);
      if (!Arrays.equals(MAGIC, magic)) throw new IOException("Not a Telegram X backup");
      int version = header.readInt();
      if (version != VERSION) throw new IOException("Unsupported backup version: " + version);
      int iterations = header.readInt();
      if (iterations < 100_000 || iterations > 2_000_000) {
        throw new IOException("Invalid key derivation parameters");
      }
      int saltLength = header.readUnsignedByte();
      if (saltLength != SALT_SIZE) throw new IOException("Invalid salt");
      salt = new byte[saltLength];
      header.readFully(salt);
      int ivLength = header.readUnsignedByte();
      if (ivLength != IV_SIZE) throw new IOException("Invalid IV");
      iv = new byte[ivLength];
      header.readFully(iv);

      SecretKeySpec key = deriveKey(password, salt, iterations);
      Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, iv);
      try (CipherInputStream decrypted = new CipherInputStream(header, cipher);
           ZipInputStream zip = new ZipInputStream(new BufferedInputStream(decrypted))) {
        int entries = 0;
        long totalBytes = 0;
        ZipEntry entry;
        byte[] buffer = new byte[BUFFER_SIZE];
        while ((entry = zip.getNextEntry()) != null) {
          if (++entries > MAX_ENTRIES) throw new IOException("Too many archive entries");
          String name = entry.getName();
          String safeName = entry.isDirectory() && name.endsWith("/")
              ? name.substring(0, name.length() - 1) : name;
          File target = safeChild(validationDirectory, safeName);
          if (entry.isDirectory()) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Cannot create directory");
          } else {
            File parent = target.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
              throw new IOException("Cannot create parent directory");
            }
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
              int count;
              long entryBytes = 0;
              while ((count = zip.read(buffer)) != -1) {
                entryBytes += count;
                totalBytes += count;
                if (entryBytes > MAX_ENTRY_SIZE || totalBytes < 0) {
                  throw new IOException("Archive size limit exceeded");
                }
                out.write(buffer, 0, count);
              }
            }
          }
          zip.closeEntry();
        }
      }
      // Only after authenticated decryption succeeds, move validated files into staging.
      moveChildren(validationDirectory, emptyStagingDirectory);
      success = true;
    } catch (IOException | GeneralSecurityException e) {
      throw e;
    } finally {
      Arrays.fill(password, '\0');
      if (salt != null) Arrays.fill(salt, (byte) 0);
      if (iv != null) Arrays.fill(iv, (byte) 0);
      deleteRecursively(validationDirectory);
      if (!success) {
        // Leave no partially imported files behind.
        File[] children = emptyStagingDirectory.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
      }
    }
  }

  private static void zipDirectory(File root, File current, ZipOutputStream zip) throws IOException {
    File[] children = current.listFiles();
    if (children == null) throw new IOException("Cannot read snapshot directory");
    Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
    byte[] buffer = new byte[BUFFER_SIZE];
    for (File child : children) {
      String relative = root.toURI().relativize(child.toURI()).getPath();
      if (relative.isEmpty() || relative.startsWith("/") || relative.contains("../")) {
        throw new IOException("Invalid path in snapshot");
      }
      if (child.isDirectory()) {
        zip.putNextEntry(new ZipEntry(relative.endsWith("/") ? relative : relative + "/"));
        zip.closeEntry();
        zipDirectory(root, child, zip);
      } else if (child.isFile()) {
        zip.putNextEntry(new ZipEntry(relative));
        try (InputStream in = new BufferedInputStream(new FileInputStream(child))) {
          int count;
          while ((count = in.read(buffer)) != -1) zip.write(buffer, 0, count);
        }
        zip.closeEntry();
      }
    }
  }

  private static File safeChild(File root, String name) throws IOException {
    if (name == null || name.isEmpty() || name.startsWith("/")
        || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
      throw new IOException("Unsafe archive path");
    }
    String[] parts = name.split("/");
    for (String part : parts) {
      if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
        throw new IOException("Unsafe archive path");
      }
    }
    File target = new File(root, name);
    String canonicalRoot = root.getCanonicalPath() + File.separator;
    if (!target.getCanonicalPath().startsWith(canonicalRoot)) {
      throw new IOException("Archive path escapes staging directory");
    }
    return target;
  }

  private static void moveChildren(File from, File to) throws IOException {
    File[] children = from.listFiles();
    if (children == null) throw new IOException("Cannot read validated archive");
    for (File child : children) {
      File target = new File(to, child.getName());
      if (target.exists()) throw new IOException("Duplicate restore path");
      if (!child.renameTo(target)) {
        copyRecursively(child, target);
        if (!deleteRecursively(child)) throw new IOException("Cannot clean temporary restore files");
      }
    }
  }

  private static void copyRecursively(File from, File to) throws IOException {
    if (from.isDirectory()) {
      if (!to.mkdirs() && !to.isDirectory()) throw new IOException("Cannot create directory");
      File[] children = from.listFiles();
      if (children == null) throw new IOException("Cannot read directory");
      for (File child : children) copyRecursively(child, new File(to, child.getName()));
    } else {
      try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
      }
    }
  }

  private static boolean deleteRecursively(File file) {
    if (file == null || !file.exists()) return true;
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) deleteRecursively(child);
    }
    return file.delete() || !file.exists();
  }

  private static SecretKeySpec deriveKey(char[] password, byte[] salt)
      throws GeneralSecurityException {
    return deriveKey(password, salt, PBKDF2_ITERATIONS);
  }

  private static SecretKeySpec deriveKey(char[] password, byte[] salt, int iterations)
      throws GeneralSecurityException {
    PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
    try {
      byte[] encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
          .generateSecret(spec).getEncoded();
      try {
        return new SecretKeySpec(encoded, "AES");
      } finally {
        Arrays.fill(encoded, (byte) 0);
      }
    } finally {
      spec.clearPassword();
    }
  }

  private static Cipher cipher(int mode, SecretKeySpec key, byte[] iv)
      throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
    cipher.updateAAD(MAGIC);
    return cipher;
  }

  private static byte[] randomBytes(int size) {
    byte[] value = new byte[size];
    new SecureRandom().nextBytes(value);
    return value;
  }

  private static void requirePassword(char[] password) {
    if (password == null || password.length < 12) {
      throw new IllegalArgumentException("Backup password must contain at least 12 characters");
    }
  }

  private static final class NonClosingOutputStream extends OutputStream {
    private final OutputStream delegate;
    NonClosingOutputStream(OutputStream delegate) { this.delegate = delegate; }
    @Override public void write(int b) throws IOException { delegate.write(b); }
    @Override public void write(byte[] b, int off, int len) throws IOException { delegate.write(b, off, len); }
    @Override public void flush() throws IOException { delegate.flush(); }
    @Override public void close() throws IOException { delegate.flush(); }
  }
}
