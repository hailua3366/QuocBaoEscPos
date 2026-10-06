package com.quocbao.escpos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.annotations.SimpleObject;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.util.YailList;

import java.util.ArrayList;
import java.util.List;

@DesignerComponent(
    version = 1,
    description = "Convert a PNG/JPG receipt image to ESC/POS raster byte chunks for 58mm Bluetooth thermal printers.",
    category = ComponentCategory.EXTENSION,
    nonVisible = true,
    iconName = "")
@SimpleObject(external = true)
public class QuocBaoEscPos extends AndroidNonvisibleComponent {

  public QuocBaoEscPos(ComponentContainer container) {
    super(container.$form());
  }

  @SimpleFunction(description = "Convert an image file to ESC/POS GS v 0 raster chunks. Use width 384 for most 58mm printers, chunkHeight 128, threshold 180. Send each returned sub-list with BluetoothClient.SendBytes.")
  public YailList ImageToChunks(String imagePath, int printerWidth, int chunkHeight, int threshold) {
    List<Object> chunks = new ArrayList<Object>();

    if (imagePath == null || imagePath.length() == 0) {
      return YailList.makeList(chunks);
    }

    String path = normalizePath(imagePath);
    Bitmap source = BitmapFactory.decodeFile(path);
    if (source == null) {
      return YailList.makeList(chunks);
    }

    if (printerWidth <= 0) {
      printerWidth = 384;
    }
    if (chunkHeight <= 0) {
      chunkHeight = 128;
    }
    if (threshold < 0) {
      threshold = 0;
    }
    if (threshold > 255) {
      threshold = 255;
    }

    int srcW = source.getWidth();
    int srcH = source.getHeight();
    int dstW = printerWidth;
    int dstH = Math.max(1, Math.round((float) srcH * (float) dstW / (float) srcW));

    Bitmap bitmap = source;
    if (srcW != dstW) {
      bitmap = Bitmap.createScaledBitmap(source, dstW, dstH, true);
    } else {
      dstH = srcH;
    }

    int widthBytes = (dstW + 7) / 8;

    for (int startY = 0; startY < dstH; startY += chunkHeight) {
      int rows = Math.min(chunkHeight, dstH - startY);
      ArrayList<Object> bytes = new ArrayList<Object>();

      // Initialize printer once before the first raster chunk.
      if (startY == 0) {
        bytes.add(27); // ESC
        bytes.add(64); // @
      }

      // GS v 0 m xL xH yL yH
      bytes.add(29);
      bytes.add(118);
      bytes.add(48);
      bytes.add(0);
      bytes.add(widthBytes & 0xFF);
      bytes.add((widthBytes >> 8) & 0xFF);
      bytes.add(rows & 0xFF);
      bytes.add((rows >> 8) & 0xFF);

      for (int y = startY; y < startY + rows; y++) {
        for (int xb = 0; xb < widthBytes; xb++) {
          int value = 0;
          for (int bit = 0; bit < 8; bit++) {
            int x = xb * 8 + bit;
            if (x < dstW) {
              int pixel = bitmap.getPixel(x, y);
              int alpha = Color.alpha(pixel);
              int r = Color.red(pixel);
              int g = Color.green(pixel);
              int b = Color.blue(pixel);

              // Blend transparent pixels onto white.
              if (alpha < 255) {
                r = (r * alpha + 255 * (255 - alpha)) / 255;
                g = (g * alpha + 255 * (255 - alpha)) / 255;
                b = (b * alpha + 255 * (255 - alpha)) / 255;
              }

              int luminance = (299 * r + 587 * g + 114 * b) / 1000;
              if (luminance < threshold) {
                value |= (1 << (7 - bit));
              }
            }
          }
          bytes.add(value);
        }
      }

      chunks.add(YailList.makeList(bytes));
    }

    // Feed paper after printing.
    ArrayList<Object> feed = new ArrayList<Object>();
    feed.add(10);
    feed.add(10);
    feed.add(10);
    chunks.add(YailList.makeList(feed));

    if (bitmap != source) {
      bitmap.recycle();
    }
    source.recycle();

    return YailList.makeList(chunks);
  }

  @SimpleFunction(description = "Returns true if the image can be decoded from the supplied file path.")
  public boolean CanReadImage(String imagePath) {
    if (imagePath == null || imagePath.length() == 0) {
      return false;
    }
    Bitmap bitmap = BitmapFactory.decodeFile(normalizePath(imagePath));
    if (bitmap == null) {
      return false;
    }
    bitmap.recycle();
    return true;
  }

  private String normalizePath(String imagePath) {
    if (imagePath.startsWith("file://")) {
      return imagePath.substring(7);
    }
    return imagePath;
  }
}
