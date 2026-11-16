package com.example.devicecontrol.ui

import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Offline QR decoder built on ZXing with morphological min-filter preprocessing
 * to bridge inter-pixel gaps on dot-matrix / backlit LCD screens.
 */
internal object QrCodeDecoder {
    private val decodeHints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    fun decodeFromImageProxy(image: ImageProxy, reader: QRCodeReader): String? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val yPlane = extractYPlane(image) ?: return null
        val cropSide = (minOf(width, height) * 0.68f).toInt().coerceAtLeast(1)
        val cropLeft = ((width - cropSide) / 2).coerceAtLeast(0)
        val cropTop = ((height - cropSide) / 2).coerceAtLeast(0)
        val centerY = cropGray(yPlane, width, cropLeft, cropTop, cropSide, cropSide)

        // 1. Standard printed QR codes in center viewfinder
        decodeGray(centerY, cropSide, cropSide, reader)?.let { return it }

        // 2. Dot-matrix LCD QR codes (bridge 1px pixel gaps)
        val centerYMin1 = minFilter(centerY, cropSide, cropSide, radius = 1)
        decodeGray(centerYMin1, cropSide, cropSide, reader)?.let { return it }

        // 3. Blue-backlit LCD QR codes have strongest contrast in the red channel
        val centerRed = extractCenterRedFromYuv(image, centerY, cropLeft, cropTop, cropSide, cropSide)
        if (centerRed != null) {
            val centerRedMin1 = minFilter(centerRed, cropSide, cropSide, radius = 1)
            decodeGray(centerRedMin1, cropSide, cropSide, reader)?.let { return it }
        }

        // 4. Higher-resolution / close-up LCD frames where pixel gaps are ~2px wide
        val centerYMin2 = minFilter(centerY, cropSide, cropSide, radius = 2)
        decodeGray(centerYMin2, cropSide, cropSide, reader)?.let { return it }

        // 5. Fallback to full frame if code is not centered
        decodeGray(yPlane, width, height, reader)?.let { return it }
        val fullYMin1 = minFilter(yPlane, width, height, radius = 1)
        return decodeGray(fullYMin1, width, height, reader)
    }

    private fun decodeGray(
        gray: ByteArray,
        width: Int,
        height: Int,
        reader: QRCodeReader,
    ): String? {
        val source: LuminanceSource = PlanarYUVLuminanceSource(
            gray,
            width,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        val candidates = arrayOf(
            BinaryBitmap(HybridBinarizer(source)),
            BinaryBitmap(GlobalHistogramBinarizer(source)),
        )
        for (bitmap in candidates) {
            try {
                return reader.decode(bitmap, decodeHints).text
            } catch (_: NotFoundException) {
                // Try next binarizer
            } catch (_: Exception) {
                // Try next binarizer
            } finally {
                reader.reset()
            }
        }
        return null
    }

    private fun extractYPlane(image: ImageProxy): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        buffer.rewind()
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1 && rowStride == width && buffer.remaining() >= width * height) {
            return ByteArray(width * height).also { buffer.get(it) }
        }
        val data = ByteArray(width * height)
        val rowBuffer = ByteArray(rowStride)
        for (row in 0 until height) {
            val bytesToRead = minOf(rowStride, buffer.remaining())
            if (bytesToRead <= 0) break
            buffer.get(rowBuffer, 0, bytesToRead)
            if (pixelStride == 1) {
                System.arraycopy(rowBuffer, 0, data, row * width, minOf(width, bytesToRead))
            } else {
                for (col in 0 until width) {
                    val idx = col * pixelStride
                    if (idx < bytesToRead) data[row * width + col] = rowBuffer[idx]
                }
            }
        }
        return data
    }

    private fun extractCenterRedFromYuv(
        image: ImageProxy,
        centerY: ByteArray,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
    ): ByteArray? {
        if (image.planes.size < 3) return null
        val vPlane = image.planes[2]
        val vBuf = vPlane.buffer
        vBuf.rewind()

        val vBytes = ByteArray(vBuf.remaining()).also { vBuf.get(it) }
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val out = ByteArray(cropWidth * cropHeight)
        for (dy in 0 until cropHeight) {
            val vRowOffset = ((cropTop + dy) / 2) * vRowStride
            val outRow = dy * cropWidth
            for (dx in 0 until cropWidth) {
                val vIdx = vRowOffset + ((cropLeft + dx) / 2) * vPixelStride
                val y = centerY[outRow + dx].toInt() and 0xFF
                val v = if (vIdx in vBytes.indices) (vBytes[vIdx].toInt() and 0xFF) - 128 else 0
                val r = (y + ((v * 1436) shr 10)).coerceIn(0, 255)
                out[outRow + dx] = r.toByte()
            }
        }
        return out
    }

    private fun cropGray(
        src: ByteArray,
        srcWidth: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            System.arraycopy(src, (top + y) * srcWidth + left, out, y * width, width)
        }
        return out
    }

    /**
     * Separable 2D min-filter (erosion on bright background / dilation of dark dots)
     * that bridges bright pixel grid gaps in dot-matrix LCD QR codes.
     */
    private fun minFilter(src: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        val tmp = ByteArray(width * height)
        val dst = ByteArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var min = 255
                val x0 = (x - radius).coerceAtLeast(0)
                val x1 = (x + radius).coerceAtMost(width - 1)
                for (k in x0..x1) {
                    val v = src[row + k].toInt() and 0xFF
                    if (v < min) min = v
                }
                tmp[row + x] = min.toByte()
            }
        }
        for (y in 0 until height) {
            val y0 = (y - radius).coerceAtLeast(0)
            val y1 = (y + radius).coerceAtMost(height - 1)
            for (x in 0 until width) {
                var min = 255
                for (k in y0..y1) {
                    val v = tmp[k * width + x].toInt() and 0xFF
                    if (v < min) min = v
                }
                dst[y * width + x] = min.toByte()
            }
        }
        return dst
    }
}
