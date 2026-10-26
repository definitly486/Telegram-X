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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Export / import of everything TDLib needs to restore the logged-in session(s)
 * on a clean installation, so the user does not have to enter phone number, code or 2FA password again.
 *
 * What is saved:
 *  - {@code files/tdlib_accounts.bin} — list of accounts of the app;
 *  - for every account directory {@code files/tdlib[N]} — all top-level files
 *    except {@code db.sqlite*} (message cache, which TDLib re-downloads on its own) and logs.
 *    The authorization keys live in {@code td.binlog}, the database is not encrypted by the app.
 *
 * The result is a single file: {@code MAGIC | version | salt | iv | AES-256-CBC(zip) | HMAC-SHA256}.
 * Keys are derived from the password with PBKDF2.
 *
 * Import is two-phase, because TDLib keeps its files open while the process is alive:
 *  1. {@link #stageImport} verifies and unpacks the backup into {@code files/pending_import};
 *  2. {@link #restartApp} restarts the process, and {@link #applyPendingImport}, called from
 *     {@code BaseApplication.onCreate()} before TDLib starts, swaps the files in.
 */
public final class LoginBackup {
  private static final byte[] MAGIC = {'T', 'G', 'X', 'B'};
  private static final int FORMAT_VERSION = 1;

  private static final int SALT_LENGTH = 16;
  private static final int IV_LENGTH = 16;
  private static final int MAC_LENGTH = 32;
  private static final int HEADER_LENGTH = MAGIC.length + 1 + SALT_LENGTH + IV_LENGTH;
  private static final int PBKDF2_ITERATIONS = 150_000;

  private static final long MAX_BACKUP_SIZE = 256L * 1024L * 1024L;
  private static final int MAX_ENTRIES = 256;

  private static final String ACCOUNTS_FILE = "tdlib_accounts.bin";
  private static final String TDLIB_PREFIX = "tdlib";
  private static final String PENDING_DIR = "pending_import";
  private static final String TEMP_FILE = "pending_import.tmp";
  private static final String READY_MARKER = "READY";

  private static final Pattern TDLIB_DIR = Pattern.compile("tdlib\\d{0,5}");
  private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}");

  public static final String FILE_EXTENSION = "tgxbackup";

  public static class BadPasswordException extends IOException {
    public BadPasswordException () {
      super("Wrong password or the backup file is corrupted");
    }
  }

  private LoginBackup () { }

  // Export

  public static boolean hasSomethingToExport (File filesDir) {
    return new File(filesDir, ACCOUNTS_FILE).isFile() && !collectFiles(filesDir).isEmpty();
  }

  public static void export (File filesDir, OutputStream out, char[] password) throws IOException, GeneralSecurityException {
    File accounts = new File(filesDir, ACCOUNTS_FILE);
    List<Source> sources = collectFiles(filesDir);
    if (!accounts.isFile() || sources.isEmpty()) {
      throw new IOException("Nothing to export: no TDLib session files found");
    }

    SecureRandom random = new SecureRandom();
    byte[] salt = new byte[SALT_LENGTH];
    byte[] iv = new byte[IV_LENGTH];
    random.nextBytes(salt);
    random.nextBytes(iv);

    byte[][] keys = deriveKeys(password, salt);
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys[0], "AES"), new IvParameterSpec(iv));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(keys[1], "HmacSHA256"));

    MacOutputStream macOut = new MacOutputStream(out, mac);
    macOut.write(MAGIC);
    macOut.write(FORMAT_VERSION);
    macOut.write(salt);
    macOut.write(iv);

    ZipOutputStream zip = new ZipOutputStream(new CipherOutputStream(macOut, cipher));
    addToZip(zip, ACCOUNTS_FILE, accounts);
    for (Source source : sources) {
      addToZip(zip, source.entryName, source.file);
    }
    zip.close(); // finishes the cipher; MacOutputStream does not close `out`

    out.write(mac.doFinal());
    out.flush();
    Arrays.fill(keys[0], (byte) 0);
    Arrays.fill(keys[1], (byte) 0);
  }

  private static final class Source {
    final String entryName;
    final File file;

    Source (String entryName, File file) {
      this.entryName = entryName;
      this.file = file;
    }
  }

  private static List<Source> collectFiles (File filesDir) {
    List<Source> result = new ArrayList<>();
    File[] children = filesDir.listFiles();
    if (children == null) {
      return result;
    }
    for (File dir : children) {
      if (!dir.isDirectory() || !TDLIB_DIR.matcher(dir.getName()).matches()) {
        continue;
      }
      File[] files = dir.listFiles();
      if (files == null) {
        continue;
      }
      for (File file : files) {
        String name = file.getName();
        if (!file.isFile() || !isSessionFileName(name)) {
          continue;
        }
        result.add(new Source(TDLIB_PREFIX + "/" + dir.getName() + "/" + name, file));
      }
    }
    return result;
  }

  private static boolean isSessionFileName (String name) {
    return SAFE_FILE_NAME.matcher(name).matches() &&
      !name.startsWith("db.sqlite") && // message cache, rebuilt by TDLib
      !name.startsWith("log");         // legacy log files
  }

  private static void addToZip (ZipOutputStream zip, String entryName, File file) throws IOException {
    zip.putNextEntry(new ZipEntry(entryName));
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[16 * 1024];
      int read;
      while ((read = in.read(buffer)) != -1) {
        zip.write(buffer, 0, read);
      }
    }
    zip.closeEntry();
  }

  // Import, phase 1: verify, decrypt and unpack into files/pending_import

  public static void stageImport (File filesDir, InputStream in, char[] password) throws IOException, GeneralSecurityException {
    File temp = new File(filesDir, TEMP_FILE);
    File pending = new File(filesDir, PENDING_DIR);
    deleteRecursive(temp);
    deleteRecursive(pending);
    try {
      copyLimited(in, temp, MAX_BACKUP_SIZE);
      long length = temp.length();
      if (length < HEADER_LENGTH + MAC_LENGTH + 1) {
        throw new IOException("Not a Telegram X login backup");
      }

      byte[] header = new byte[HEADER_LENGTH];
      try (InputStream headerIn = new FileInputStream(temp)) {
        readFully(headerIn, header);
      }
      for (int i = 0; i < MAGIC.length; i++) {
        if (header[i] != MAGIC[i]) {
          throw new IOException("Not a Telegram X login backup");
        }
      }
      if (header[MAGIC.length] != FORMAT_VERSION) {
        throw new IOException("Unsupported backup version: " + header[MAGIC.length]);
      }
      byte[] salt = Arrays.copyOfRange(header, MAGIC.length + 1, MAGIC.length + 1 + SALT_LENGTH);
      byte[] iv = Arrays.copyOfRange(header, MAGIC.length + 1 + SALT_LENGTH, HEADER_LENGTH);

      byte[][] keys = deriveKeys(password, salt);
      try {
        // 1. Authenticate before touching anything
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(keys[1], "HmacSHA256"));
        long macStart = length - MAC_LENGTH;
        byte[] storedMac = new byte[MAC_LENGTH];
        try (RandomAccessFile raf = new RandomAccessFile(temp, "r")) {
          byte[] buffer = new byte[16 * 1024];
          long remaining = macStart;
          while (remaining > 0) {
            int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) {
              throw new IOException("Unexpected end of file");
            }
            mac.update(buffer, 0, read);
            remaining -= read;
          }
          raf.readFully(storedMac);
        }
        if (!MessageDigest.isEqual(mac.doFinal(), storedMac)) {
          throw new BadPasswordException();
        }

        // 2. Decrypt and unpack
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keys[0], "AES"), new IvParameterSpec(iv));
        try (FileInputStream fis = new FileInputStream(temp)) {
          skipFully(fis, HEADER_LENGTH);
          BoundedInputStream bounded = new BoundedInputStream(new BufferedInputStream(fis), macStart - HEADER_LENGTH);
          unpack(new ZipInputStream(new CipherInputStream(bounded, cipher)), pending);
        }
      } finally {
        Arrays.fill(keys[0], (byte) 0);
        Arrays.fill(keys[1], (byte) 0);
      }

      File accounts = new File(pending, ACCOUNTS_FILE);
      File tdlib = new File(pending, TDLIB_PREFIX);
      File[] dirs = tdlib.listFiles();
      if (!accounts.isFile() || dirs == null || dirs.length == 0) {
        throw new IOException("The backup does not contain a session");
      }
      if (!new File(pending, READY_MARKER).createNewFile()) {
        throw new IOException("Unable to finish staging");
      }
    } catch (IOException | GeneralSecurityException | RuntimeException e) {
      deleteRecursive(pending);
      throw e;
    } finally {
      deleteRecursive(temp);
    }
  }

  private static void unpack (ZipInputStream zip, File targetRoot) throws IOException {
    if (!targetRoot.mkdirs()) {
      throw new IOException("Unable to create " + targetRoot);
    }
    long total = 0;
    int count = 0;
    ZipEntry entry;
    byte[] buffer = new byte[16 * 1024];
    while ((entry = zip.getNextEntry()) != null) {
      if (++count > MAX_ENTRIES) {
        throw new IOException("Too many entries");
      }
      File target = resolveEntry(targetRoot, entry);
      if (target == null) {
        continue; // never trust names from the archive: unknown entries are ignored
      }
      File parent = target.getParentFile();
      if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
        throw new IOException("Unable to create " + parent);
      }
      try (OutputStream out = new FileOutputStream(target)) {
        int read;
        while ((read = zip.read(buffer)) != -1) {
          total += read;
          if (total > MAX_BACKUP_SIZE) {
            throw new IOException("Backup is too large");
          }
          out.write(buffer, 0, read);
        }
      }
    }
  }

  private static File resolveEntry (File root, ZipEntry entry) {
    if (entry.isDirectory()) {
      return null;
    }
    String name = entry.getName();
    if (ACCOUNTS_FILE.equals(name)) {
      return new File(root, ACCOUNTS_FILE);
    }
    String[] parts = name.split("/", -1);
    if (parts.length == 3 && TDLIB_PREFIX.equals(parts[0]) && TDLIB_DIR.matcher(parts[1]).matches() && isSessionFileName(parts[2])) {
      return new File(new File(new File(root, TDLIB_PREFIX), parts[1]), parts[2]);
    }
    return null;
  }

  // Import, phase 2: runs on the next process start, before anything opens TDLib files

  /**
   * Must be called as early as possible, before {@code UI.initApp()}.
   * Never throws.
   */
  public static void applyPendingImport (File filesDir) {
    File pending = new File(filesDir, PENDING_DIR);
    File temp = new File(filesDir, TEMP_FILE);
    try {
      if (temp.exists()) {
        deleteRecursive(temp);
      }
      if (!pending.exists()) {
        return;
      }
      if (!new File(pending, READY_MARKER).isFile()) {
        return; // staging was not finished: just clean up in `finally`
      }

      File[] existing = filesDir.listFiles();
      if (existing != null) {
        for (File child : existing) {
          if (child.isDirectory() && TDLIB_DIR.matcher(child.getName()).matches()) {
            deleteRecursive(child);
          }
        }
      }
      File accountsTarget = new File(filesDir, ACCOUNTS_FILE);
      deleteRecursive(accountsTarget);

      File[] stagedDirs = new File(pending, TDLIB_PREFIX).listFiles();
      if (stagedDirs != null) {
        for (File dir : stagedDirs) {
          move(dir, new File(filesDir, dir.getName()));
        }
      }
      move(new File(pending, ACCOUNTS_FILE), accountsTarget);
    } catch (Throwable t) {
      android.util.Log.e("LoginBackup", "Unable to apply pending import", t);
    } finally {
      deleteRecursive(pending);
    }
  }

  // Restart

  public static void restartApp (Context context) {
    Context app = context.getApplicationContext();
    Intent intent = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
    if (intent != null) {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
      int flags = PendingIntent.FLAG_CANCEL_CURRENT;
      if (Build.VERSION.SDK_INT >= 23) {
        flags |= PendingIntent.FLAG_IMMUTABLE;
      }
      PendingIntent pendingIntent = PendingIntent.getActivity(app, 0x7e57, intent, flags);
      AlarmManager alarm = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
      if (alarm != null) {
        alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 400, pendingIntent);
      }
    }
    Process.killProcess(Process.myPid());
    System.exit(0);
  }

  // Crypto & IO helpers

  private static byte[][] deriveKeys (char[] password, byte[] salt) throws GeneralSecurityException {
    PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, 512);
    try {
      byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
      byte[][] result = {
        Arrays.copyOfRange(derived, 0, 32),
        Arrays.copyOfRange(derived, 32, 64)
      };
      Arrays.fill(derived, (byte) 0);
      return result;
    } finally {
      spec.clearPassword();
    }
  }

  private static void copyLimited (InputStream in, File target, long limit) throws IOException {
    try (OutputStream out = new FileOutputStream(target)) {
      byte[] buffer = new byte[16 * 1024];
      long total = 0;
      int read;
      while ((read = in.read(buffer)) != -1) {
        total += read;
        if (total > limit) {
          throw new IOException("File is too large to be a login backup");
        }
        out.write(buffer, 0, read);
      }
    }
  }

  private static void readFully (InputStream in, byte[] target) throws IOException {
    int offset = 0;
    while (offset < target.length) {
      int read = in.read(target, offset, target.length - offset);
      if (read == -1) {
        throw new IOException("Unexpected end of file");
      }
      offset += read;
    }
  }

  private static void skipFully (InputStream in, long count) throws IOException {
    while (count > 0) {
      long skipped = in.skip(count);
      if (skipped <= 0) {
        if (in.read() == -1) {
          throw new IOException("Unexpected end of file");
        }
        skipped = 1;
      }
      count -= skipped;
    }
  }

  private static void move (File from, File to) throws IOException {
    if (from.renameTo(to)) {
      return;
    }
    copyRecursive(from, to);
    deleteRecursive(from);
  }

  private static void copyRecursive (File from, File to) throws IOException {
    if (from.isDirectory()) {
      if (!to.isDirectory() && !to.mkdirs()) {
        throw new IOException("Unable to create " + to);
      }
      File[] children = from.listFiles();
      if (children != null) {
        for (File child : children) {
          copyRecursive(child, new File(to, child.getName()));
        }
      }
    } else {
      try (InputStream in = new FileInputStream(from)) {
        copyLimited(in, to, Long.MAX_VALUE);
      }
    }
  }

  private static void deleteRecursive (File file) {
    if (!file.exists()) {
      return;
    }
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          deleteRecursive(child);
        }
      }
    }
    //noinspection ResultOfMethodCallIgnored
    file.delete();
  }

  /** Writes through and feeds the MAC; does NOT close the underlying stream. */
  private static final class MacOutputStream extends FilterOutputStream {
    private final Mac mac;

    MacOutputStream (OutputStream out, Mac mac) {
      super(out);
      this.mac = mac;
    }

    @Override
    public void write (int b) throws IOException {
      out.write(b);
      mac.update((byte) b);
    }

    @Override
    public void write (byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
      mac.update(b, off, len);
    }

    @Override
    public void close () throws IOException {
      flush();
    }
  }

  private static final class BoundedInputStream extends FilterInputStream {
    private long remaining;

    BoundedInputStream (InputStream in, long limit) {
      super(in);
      this.remaining = limit;
    }

    @Override
    public int read () throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int result = in.read();
      if (result != -1) {
        remaining--;
      }
      return result;
    }

    @Override
    public int read (byte[] b, int off, int len) throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int read = in.read(b, off, (int) Math.min(len, remaining));
      if (read > 0) {
        remaining -= read;
      }
      return read;
    }
  }
}
