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
    version = 2,
    description = "Convert PNG/JPG receipt images to ESC/POS raster byte chunks for 58mm Bluetooth thermal printers.",
    category = ComponentCategory.EXTENSION,
    nonVisible = true,
    iconName = "")
@SimpleObject(external = true)
public class QuocBaoEscPos extends AndroidNonvisibleComponent {

  public QuocBaoEscPos(ComponentContainer container) {
    super(container.$form());
  }

  @SimpleFunction(description = "Convert an image file to ESC/POS GS v 0 raster chunks. Recommended: width 384, chunkHeight 24, threshold 180.")
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

    if (printerWidth <= 0) printerWidth = 384;
    if (chunkHeight <= 0) chunkHeight = 24;
    threshold = clampThreshold(threshold);

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
            if (x < dstW && isDark(bitmap.getPixel(x, y), threshold)) {
              value |= (1 << (7 - bit));
            }
          }
          bytes.add(value);
        }
      }

      chunks.add(YailList.makeList(bytes));
    }

    chunks.add(feedChunk());

    if (bitmap != source) bitmap.recycle();
    source.recycle();

    return YailList.makeList(chunks);
  }

  @SimpleFunction(description = "Returns true if the image can be decoded from the supplied file path.")
  public boolean CanReadImage(String imagePath) {
    if (imagePath == null || imagePath.length() == 0) return false;
    Bitmap bitmap = BitmapFactory.decodeFile(normalizePath(imagePath));
    if (bitmap == null) return false;
    bitmap.recycle();
    return true;
  }

  @SimpleFunction(description = "Analyze the saved image without printing. Returns width, height and number of dark pixels. If dark=0 the saved image is blank/white.")
  public String ImageStats(String imagePath, int threshold) {
    if (imagePath == null || imagePath.length() == 0) return "ERROR: empty path";
    Bitmap bitmap = BitmapFactory.decodeFile(normalizePath(imagePath));
    if (bitmap == null) return "ERROR: cannot decode image";

    threshold = clampThreshold(threshold);
    int w = bitmap.getWidth();
    int h = bitmap.getHeight();
    long dark = 0;

    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        if (isDark(bitmap.getPixel(x, y), threshold)) dark++;
      }
    }

    long total = (long) w * (long) h;
    bitmap.recycle();
    return w + "x" + h + " | dark=" + dark + " | total=" + total;
  }

  @SimpleFunction(description = "Create a tiny ESC/POS raster test: a solid black horizontal bar. Use this to verify whether the printer supports GS v 0 without using an image file.")
  public YailList BlackBarTestChunks(int printerWidth, int rows) {
    List<Object> chunks = new ArrayList<Object>();
    if (printerWidth <= 0) printerWidth = 384;
    if (rows <= 0) rows = 8;
    if (rows > 64) rows = 64;

    int widthBytes = (printerWidth + 7) / 8;
    ArrayList<Object> bytes = new ArrayList<Object>();

    bytes.add(27); // ESC
    bytes.add(64); // @
    bytes.add(29);
    bytes.add(118);
    bytes.add(48);
    bytes.add(0);
    bytes.add(widthBytes & 0xFF);
    bytes.add((widthBytes >> 8) & 0xFF);
    bytes.add(rows & 0xFF);
    bytes.add((rows >> 8) & 0xFF);

    for (int y = 0; y < rows; y++) {
      for (int xb = 0; xb < widthBytes; xb++) {
        bytes.add(255);
      }
    }

    chunks.add(YailList.makeList(bytes));
    chunks.add(feedChunk());
    return YailList.makeList(chunks);
  }

  private ArrayList<Object> feedChunk() {
    ArrayList<Object> feed = new ArrayList<Object>();
    feed.add(10);
    feed.add(10);
    feed.add(10);
    return feed;
  }

  private int clampThreshold(int threshold) {
    if (threshold < 0) return 0;
    if (threshold > 255) return 255;
    return threshold;
  }

  private boolean isDark(int pixel, int threshold) {
    int alpha = Color.alpha(pixel);
    int r = Color.red(pixel);
    int g = Color.green(pixel);
    int b = Color.blue(pixel);

    if (alpha < 255) {
      r = (r * alpha + 255 * (255 - alpha)) / 255;
      g = (g * alpha + 255 * (255 - alpha)) / 255;
      b = (b * alpha + 255 * (255 - alpha)) / 255;
    }

    int luminance = (299 * r + 587 * g + 114 * b) / 1000;
    return luminance < threshold;
  }

  private String normalizePath(String imagePath) {
    if (imagePath.startsWith("file://")) {
      return imagePath.substring(7);
    }
    return imagePath;
  }
}
