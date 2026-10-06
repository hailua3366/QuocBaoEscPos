# QuocBaoEscPos

MIT App Inventor extension for converting a receipt image (PNG/JPG) into ESC/POS raster byte chunks.

## Intended use

1. Draw the Vietnamese receipt on an App Inventor Canvas.
2. Save it as `hoadon.png`.
3. Call `QuocBaoEscPos.ImageToChunks(imagePath, 384, 128, 180)`.
4. Loop through the returned list.
5. For each sub-list, call `BluetoothClient.SendBytes`.

This prints Vietnamese as pixels, so the printer does not need a Vietnamese text code page.

Recommended values for a 58 mm / 384-dot printer:
- printerWidth: 384
- chunkHeight: 128
- threshold: 180

The GitHub Actions workflow builds the extension and uploads the resulting `.aix` as an artifact.
