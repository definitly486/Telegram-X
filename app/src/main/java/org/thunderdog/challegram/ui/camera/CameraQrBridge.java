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
package org.thunderdog.challegram.ui.camera;

import android.graphics.ImageFormat;
import android.graphics.RectF;
import android.media.Image;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.camera.core.ImageProxy;
import androidx.core.content.ContextCompat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.detector.FinderPattern;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.ui.camera.legacy.CameraApiLegacy;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraQrBridge {
  public final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

  private final CameraDelegate delegate;
  private final Executor mainExecutor;
  private final QRCodeReader zxingReader = new QRCodeReader();

  public CameraQrBridge (CameraManager<?> manager) {
    this.delegate = manager.delegate;
    this.mainExecutor = ContextCompat.getMainExecutor(manager.context);

  }

  public void destroy () {
    backgroundExecutor.shutdown();
  }

  @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
  public void processImage (ImageProxy proxy) {
    @SuppressWarnings("UnsafeOptInUsageError") Image mediaImage = proxy.getImage();

    if (mediaImage == null) {
      proxy.close();
      return;
    }

    zxingImplementation(bufferAsBytes(proxy.getPlanes()[0].getBuffer()), proxy.getWidth(), proxy.getHeight(), proxy.getImageInfo().getRotationDegrees(), proxy::close);
  }

  public void processImage (byte[] data, int previewWidth, int previewHeight, CameraApiLegacy legacyApi) {
    int rotation = delegate.getCurrentCameraOrientation();

    zxingImplementation(data, previewWidth, previewHeight, rotation, legacyApi::notifyCanReadNextFrame);
  }

  public boolean isGmsImplementationSupported () {
    return false;
  }

  @SuppressWarnings("SuspiciousNameCombination")
  private void zxingImplementation (byte[] data, int width, int height, int rotation, @Nullable Runnable onFinish) {
    backgroundExecutor.submit(() -> {
      try {
        int sensorRotation = delegate.getCurrentCameraSensorOrientation();
        Result match = zxingImplementationImpl(data, width, height, rotation);
        if (match != null && match.getText() != null && !match.getText().isEmpty()) {
          RectF zxingBox;
          if (sensorRotation != rotation && U.isRotated(sensorRotation)) {
            zxingBox = zxingBoundingBox(match, rotation, true, width, height);
            mainExecutor.execute(() -> delegate.onQrCodeFound(match.getText(), zxingBox, height, width, rotation, true));
          } else {
            zxingBox = zxingBoundingBox(match, rotation, false, width, height);
            mainExecutor.execute(() -> delegate.onQrCodeFound(match.getText(), zxingBox, width, height, rotation, true));
          }
        } else {
          mainExecutor.execute(delegate::onQrCodeNotFound);
        }
      } catch (Exception ex) {
        if (ex instanceof NotFoundException) {
          mainExecutor.execute(delegate::onQrCodeNotFound);
        } else {
          Log.e(Log.TAG_CAMERA, ex);
        }
      } finally {
        if (onFinish != null) onFinish.run();
      }
    });
  }

  private RectF zxingBoundingBox (Result result, int rotation, boolean sensorRotationInverted, int width, int height) {
    // ordered in: bottom-left, top-left, top-right
    if (result.getResultPoints().length < 3) return null;

    ResultPoint[] points = result.getResultPoints();
    ResultPoint bottomLeft, topLeft, topRight;
    int moduleSize;

    if (U.isRotated(rotation)) {
      bottomLeft = points[2];
      topLeft = points[1];
      topRight = points[0];
    } else {
      bottomLeft = points[0];
      topLeft = points[1];
      topRight = points[2];
    }

    if (bottomLeft instanceof FinderPattern) {
      moduleSize = (int) ((FinderPattern) bottomLeft).getEstimatedModuleSize() * 2;
    } else {
      moduleSize = 0;
    }

    int x1 = (int) Math.min(Math.min(topLeft.getX(), topRight.getX()), bottomLeft.getX());
    int x2 = (int) Math.max(Math.max(topLeft.getX(), topRight.getX()), bottomLeft.getX());
    int y1 = (int) Math.min(Math.min(topLeft.getY(), topRight.getY()), bottomLeft.getY());
    int y2 = (int) Math.max(Math.max(topLeft.getY(), topRight.getY()), bottomLeft.getY());

    if (sensorRotationInverted) {
      // these rotations need inverting zones
      int px1 = width - x1;
      int px2 = width - x2;
      x1 = px2;
      x2 = px1;
    }

    return new RectF(
      x1 - moduleSize, y1 - moduleSize, x2 + moduleSize, y2 + moduleSize
    );
  }

  @SuppressWarnings("SuspiciousNameCombination")
  private Result zxingImplementationImpl (byte[] data, int width, int height, int rotation) throws FormatException, ChecksumException, NotFoundException {
    if (rotation != 0) {
      data = rotateYuvImage(data, width, height, rotation);
    }

    if (U.isRotated(rotation)) {
      int prevWidth = width;
      width = height;
      height = prevWidth;
    }

    PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
      data,
      width,
      height,
      0,
      0,
      width,
      height,
      rotation == 180
    );

    return zxingReader.decode(new BinaryBitmap(new HybridBinarizer(source)));
  }

  private byte[] bufferAsBytes (ByteBuffer buffer) {
    buffer.rewind();
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return bytes;
  }

  private byte[] rotateYuvImage (byte[] data, int width, int height, int degrees) {
    if (degrees == 0 || degrees % 90 != 0) return data;

    byte[] rotatedData = new byte[data.length];

    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        switch (degrees) {
          case 90: {
            rotatedData[x * height + height - y - 1] = data[x + y * width];
            break;
          }

          case 180: {
            rotatedData[width * (height - y - 1) + width - x - 1] = data[x + y * width];
            break;
          }

          case 270: {
            rotatedData[y + x * height] = data[y * width + width - x - 1];
            break;
          }
        }
      }
    }

    return rotatedData;
  }
}
