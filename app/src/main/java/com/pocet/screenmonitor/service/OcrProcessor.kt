package com.pocet.screenmonitor.service

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.util.Log
import android.util.Size
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pocet.screenmonitor.model.BalanceResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import timber.log.Timber

class OcrProcessor {

    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val processingJob = SupervisorJob()
    private val processingScope = CoroutineScope(processingJob + Dispatchers.IO)
    private var isProcessing = false

    /**
     * Process an ImageProxy from ImageReader and return BalanceResult
     * @param targetAccountType If specified, only look for this account type. If null, detect both.
     */
    suspend fun processImage(image: Image, targetAccountType: BalanceResult.AccountType? = null): BalanceResult = withContext(Dispatchers.IO) {
        if (isProcessing) {
            return@withContext BalanceResult.unknown("Already processing")
        }

        isProcessing = true
        try {
            // Convert Image to Bitmap for ML Kit
            val bitmap = imageToBitmap(image)
            if (bitmap == null) {
                return@withContext BalanceResult.unknown("Failed to convert image")
            }

            // Downscale for faster OCR
            val scaledBitmap = downscaleBitmap(bitmap)

            // Create InputImage from Bitmap
            val inputImage = InputImage.fromBitmap(scaledBitmap, 0)

            // Run text recognition
            val task = textRecognizer.process(inputImage)
            val mlKitText = Tasks.await(task)

            // Process with TextParser
            val parser = TextParser()
            val result = parser.parseText(mlKitText, targetAccountType)

            Timber.d("OCR Result: ${result.accountType}, Amount: ${result.amount}, Confidence: ${result.confidence}")

            result
        } catch (e: Exception) {
            Timber.e(e, "OCR processing failed")
            BalanceResult.unknown("Error: ${e.message}")
        } finally {
            isProcessing = false
            image.close()
        }
    }

    /**
     * Process ImageReader's latest image
     */
    suspend fun processImageReader(imageReader: ImageReader, targetAccountType: BalanceResult.AccountType? = null): BalanceResult = withContext(Dispatchers.IO) {
        val image = imageReader.acquireLatestImage()
            ?: return@withContext BalanceResult.unknown("No image available")

        return@withContext processImage(image, targetAccountType)
    }

    /**
     * Convert Android Media Image to Bitmap
     * Handles both RGBA_8888 (from ImageReader) and YUV_420_888 formats
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val width = image.width
        val height = image.height

        // Handle RGBA_8888 format (from ImageReader with PixelFormat.RGBA_8888)
        if (image.format == android.graphics.PixelFormat.RGBA_8888 || planes.size == 1) {
            val buffer = planes[0].buffer
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }

        // Handle YUV_420_888 format
        if (image.format == ImageFormat.YUV_420_888 && planes.size >= 3) {
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // Y plane
            yBuffer.get(nv21, 0, ySize)

            // VU interleaved (NV21 format)
            val uvStride = planes[1].pixelStride
            val uvRowStride = planes[1].rowStride

            var nv21Index = ySize
            for (row in 0 until height / 2) {
                val uOffset = row * uvRowStride
                val vOffset = row * uvRowStride

                for (col in 0 until width / 2) {
                    val uIndex = uOffset + col * uvStride
                    val vIndex = vOffset + col * uvStride

                    if (uIndex < uBuffer.capacity() && vIndex < vBuffer.capacity()) {
                        uBuffer.position(uIndex)
                        vBuffer.position(vIndex)
                        nv21[nv21Index++] = vBuffer.get() // V first in NV21
                        nv21[nv21Index++] = uBuffer.get() // U second in NV21
                    }
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            // Use compressToByteArray with pre-allocated byte array, then decode
            val rect = Rect(0, 0, width, height)
            val jpegData = ByteArray(width * height * 2) // Estimate JPEG size
            val bytesWritten = yuvImage.compressToByteArray(rect, jpegData)
            return android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, bytesWritten)
        }

        Timber.w("Unsupported image format: ${image.format}, planes: ${planes.size}")
        return null
    }

    /**
     * Downscale bitmap for faster OCR processing
     */
    fun downscaleBitmap(bitmap: Bitmap, maxDimension: Int = 1920): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }

        val scale = if (width > height) {
            maxDimension.toFloat() / width
        } else {
            maxDimension.toFloat() / height
        }

        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    fun close() {
        processingJob.cancel()
        textRecognizer.close()
    }

    companion object {
        private const val TAG = "OcrProcessor"
    }
}