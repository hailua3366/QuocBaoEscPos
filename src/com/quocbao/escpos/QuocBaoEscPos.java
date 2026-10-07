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

@DesignerComponent(
    version = 5,
    description = "Convert PNG/JPG receipt images to ESC/POS raster byte chunks for 58mm Bluetooth thermal printers.",
    category = ComponentCategory.EXTENSION,
    nonVisible = true,
    iconName = "")
@SimpleObject(external = true)
public class QuocBaoEscPos extends AndroidNonvisibleComponent {

  private final ArrayList<YailList> preparedChunks = new ArrayList<YailList>();

  public QuocBaoEscPos(ComponentContainer container) {
    super(container.$form());
  }

  @SimpleFunction(description = "Prepare an image for ESC/POS printing. Automatically trims blank space below the last dark pixel. Recommended: width 384, chunkHeight 24, threshold 200.")
  public int PrepareImage(String imagePath, int printerWidth, int chunkHeight, int threshold) {
    preparedChunks.clear();

    if (imagePath == null || imagePath.length() == 0) return 0;

    Bitmap source = BitmapFactory.decodeFile(normalizePath(imagePath));
    if (source == null) return 0;

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

    // Find the last row containing dark content and keep only a small bottom margin.
    int lastDarkY = -1;
    outer:
    for (int y = dstH - 1; y >= 0; y--) {
      for (int x = 0; x < dstW; x++) {
        if (isDark(bitmap.getPixel(x, y), threshold)) {
          lastDarkY = y;
          break outer;
        }
      }
    }

    int printHeight = dstH;
    if (lastDarkY >= 0) {
      int bottomMargin = 12; // printer dots, keeps just enough paper to tear comfortably
      printHeight = Math.min(dstH, lastDarkY + 1 + bottomMargin);
    }

    int widthBytes = (dstW + 7) / 8;

    for (int startY = 0; startY < printHeight; startY += chunkHeight) {
      int rows = Math.min(chunkHeight, printHeight - startY);
      ArrayList<Object> bytes = new ArrayList<Object>();

      if (startY == 0) {
        bytes.add(Integer.valueOf(27)); // ESC
        bytes.add(Integer.valueOf(64)); // @
      }

      // GS v 0 m xL xH yL yH
      bytes.add(Integer.valueOf(29));
      bytes.add(Integer.valueOf(118));
      bytes.add(Integer.valueOf(48));
      bytes.add(Integer.valueOf(0));
      bytes.add(Integer.valueOf(widthBytes & 0xFF));
      bytes.add(Integer.valueOf((widthBytes >> 8) & 0xFF));
      bytes.add(Integer.valueOf(rows & 0xFF));
      bytes.add(Integer.valueOf((rows >> 8) & 0xFF));

      for (int y = startY; y < startY + rows; y++) {
        for (int xb = 0; xb < widthBytes; xb++) {
          int value = 0;
          for (int bit = 0; bit < 8; bit++) {
            int x = xb * 8 + bit;
            if (x < dstW && isDark(bitmap.getPixel(x, y), threshold)) {
              value |= (1 << (7 - bit));
            }
          }
          bytes.add(Integer.valueOf(value));
        }
      }

      preparedChunks.add(YailList.makeList(bytes));
    }

    // Feed enough paper after the last printed row so the final lines
    // clear the print head / tear edge, while still keeping waste small.
    // 4 LF is about 12-15 mm on common 58 mm ESC/POS printers.
    ArrayList<Object> feed = new ArrayList<Object>();
    feed.add(Integer.valueOf(10));
    feed.add(Integer.valueOf(10));
    feed.add(Integer.valueOf(10));
    feed.add(Integer.valueOf(10));
    preparedChunks.add(YailList.makeList(feed));

    if (bitmap != source) bitmap.recycle();
    source.recycle();

    return preparedChunks.size();
  }

  @SimpleFunction(description = "Return one prepared flat byte chunk. Index starts at 1. Pass this directly to BluetoothClient.SendBytes.")
  public YailList GetPreparedChunk(int index) {
    if (index < 1 || index > preparedChunks.size()) {
      return YailList.makeEmptyList();
    }
    return preparedChunks.get(index - 1);
  }

  @SimpleFunction(description = "Return the number of currently prepared chunks.")
  public int PreparedChunkCount() {
    return preparedChunks.size();
  }

  @SimpleFunction(description = "Clear prepared chunks from memory.")
  public void ClearPrepared() {
    preparedChunks.clear();
  }

  @SimpleFunction(description = "Legacy method. Convert an image file to a nested list of ESC/POS chunks.")
  public YailList ImageToChunks(String imagePath, int printerWidth, int chunkHeight, int threshold) {
    int count = PrepareImage(imagePath, printerWidth, chunkHeight, threshold);
    ArrayList<Object> outer = new ArrayList<Object>();
    for (int i = 1; i <= count; i++) {
      outer.add(GetPreparedChunk(i));
    }
    return YailList.makeList(outer);
  }

  @SimpleFunction(description = "Returns true if the image can be decoded from the supplied file path.")
  public boolean CanReadImage(String imagePath) {
    if (imagePath == null || imagePath.length() == 0) return false;
    Bitmap bitmap = BitmapFactory.decodeFile(normalizePath(imagePath));
    if (bitmap == null) return false;
    bitmap.recycle();
    return true;
  }

  @SimpleFunction(description = "Analyze the saved image without printing. Returns width, height and number of dark pixels.")
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

  @SimpleFunction(description = "Prepare a tiny ESC/POS black-bar test. Returns the number of prepared chunks.")
  public int PrepareBlackBarTest(int printerWidth, int rows) {
    preparedChunks.clear();
    if (printerWidth <= 0) printerWidth = 384;
    if (rows <= 0) rows = 8;
    if (rows > 64) rows = 64;

    int widthBytes = (printerWidth + 7) / 8;
    ArrayList<Object> bytes = new ArrayList<Object>();

    bytes.add(Integer.valueOf(27));
    bytes.add(Integer.valueOf(64));
    bytes.add(Integer.valueOf(29));
    bytes.add(Integer.valueOf(118));
    bytes.add(Integer.valueOf(48));
    bytes.add(Integer.valueOf(0));
    bytes.add(Integer.valueOf(widthBytes & 0xFF));
    bytes.add(Integer.valueOf((widthBytes >> 8) & 0xFF));
    bytes.add(Integer.valueOf(rows & 0xFF));
    bytes.add(Integer.valueOf((rows >> 8) & 0xFF));

    for (int y = 0; y < rows; y++) {
      for (int xb = 0; xb < widthBytes; xb++) {
        bytes.add(Integer.valueOf(255));
      }
    }

    preparedChunks.add(YailList.makeList(bytes));

    ArrayList<Object> feed = new ArrayList<Object>();
    feed.add(Integer.valueOf(10));
    preparedChunks.add(YailList.makeList(feed));

    return preparedChunks.size();
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
