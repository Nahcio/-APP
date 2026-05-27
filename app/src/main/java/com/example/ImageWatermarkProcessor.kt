package com.example

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.max

object ImageWatermarkProcessor {

    /**
     * Rotates a bitmap if needed according to Exif orientation.
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Appends a styled watermark onto the high-resolution Bitmap.
     */
    fun applyWatermark(
        bitmap: Bitmap,
        config: WatermarkConfig,
        dateTime: String,
        coordinates: String,
        address: String
    ): Bitmap {
        // Create an editable copy of the bitmap
        val workingBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(workingBitmap)
        val w = workingBitmap.width.toFloat()
        val h = workingBitmap.height.toFloat()

        // Proportional sizing based on the shorter dimension
        val shortDim = if (w < h) w else h
        val fontSize = (shortDim * 0.024f).coerceAtLeast(20f)
        val lineSpacing = fontSize * 0.4f
        val padding = shortDim * 0.04f

        // Prepare texts according to configurations
        val lines = mutableListOf<Pair<String, String>>() // Label to Value
        if (config.showCustomText && config.customText.isNotEmpty()) {
            lines.add("" to config.customText)
        }
        if (config.showDate) {
            lines.add("时间" to dateTime)
        }
        if (config.showLocation && address.isNotEmpty()) {
            lines.add("地点" to address)
        }
        if (config.showCoordinates && coordinates.isNotEmpty()) {
            lines.add("经纬" to coordinates)
        }

        if (lines.isEmpty()) return workingBitmap

        // Measure layout dimensions
        val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val paintValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        var maxLineWidth = 0f
        val lineHeights = FloatArray(lines.size)
        var totalTextHeight = 0f

        for (i in lines.indices) {
            val (label, value) = lines[i]
            val fullText = if (label.isEmpty()) value else "【$label】$value"
            val textWidth = paintValue.measureText(fullText)
            if (textWidth > maxLineWidth) {
                maxLineWidth = textWidth
            }
            lineHeights[i] = fontSize
            totalTextHeight += fontSize
            if (i < lines.size - 1) {
                totalTextHeight += lineSpacing
            }
        }

        // Add padding around the box
        val boxWidth = maxLineWidth + padding * 2f
        val boxHeight = totalTextHeight + padding * 2f

        // Calculate watermark starting coordinates based on position choice
        val startX: Float
        val startY: Float

        when (config.position) {
            WatermarkPosition.TOP_LEFT -> {
                startX = padding
                startY = padding
            }
            WatermarkPosition.TOP_RIGHT -> {
                startX = w - boxWidth - padding
                startY = padding
            }
            WatermarkPosition.BOTTOM_LEFT -> {
                startX = padding
                startY = h - boxHeight - padding
            }
            WatermarkPosition.BOTTOM_RIGHT -> {
                startX = w - boxWidth - padding
                startY = h - boxHeight - padding
            }
        }

        // Design-driven theme drawer
        when (config.theme) {
            WatermarkTheme.HIGH_DENSITY -> {
                // Style: High Density "Ice Blue/Slate" design with modern left-side accent
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#BA090D14") // Translucent intense dark slate
                    style = Paint.Style.FILL
                }
                val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FFD0E4FF") // Ice Blue Accent line
                    style = Paint.Style.FILL
                }
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#22FFFFFF") // Subtle thin outer outline
                    style = Paint.Style.STROKE
                    strokeWidth = (shortDim * 0.0015f).coerceAtLeast(1f)
                }

                val rect = RectF(startX, startY, startX + boxWidth + shortDim * 0.015f, startY + boxHeight)
                val rx = shortDim * 0.012f
                
                canvas.drawRoundRect(rect, rx, rx, bgPaint)
                canvas.drawRoundRect(rect, rx, rx, borderPaint)

                // Draw thick Left Accent Vertical Bar
                val barWidth = shortDim * 0.008f
                val barRect = RectF(startX, startY, startX + barWidth, startY + boxHeight)
                canvas.drawRect(barRect, accentPaint)

                paintLabel.color = Color.parseColor("#FFD0E4FF") // #D0E4FF Ice Blue title/label
                paintValue.color = Color.parseColor("#FFF1F5F9") // Cozy Slate-50 bright text
                var currentY = startY + padding + fontSize * 0.85f

                for (i in lines.indices) {
                    val (label, value) = lines[i]
                    val xOffset = startX + padding + barWidth

                    if (label.isEmpty()) {
                        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#22FFFFFF")
                            strokeWidth = (shortDim * 0.001f).coerceAtLeast(1f)
                        }
                        val dividerY = currentY - fontSize * 0.3f
                        canvas.drawLine(xOffset, dividerY, startX + boxWidth - padding, dividerY, dividerPaint)
                        
                        paintLabel.textSize = fontSize * 0.95f
                        canvas.drawText("📝  $value", xOffset, currentY + fontSize * 0.3f, paintLabel)
                    } else {
                        val iconLabel = when (label) {
                            "时间" -> "📅  $label: "
                            "地点" -> "📍  $label: "
                            "经纬" -> "🌐  $label: "
                            else -> "🔹  $label: "
                        }
                        paintLabel.textSize = fontSize
                        canvas.drawText(iconLabel, xOffset, currentY, paintLabel)
                        val lblWidth = paintLabel.measureText(iconLabel)
                        canvas.drawText(value, xOffset + lblWidth, currentY, paintValue)
                    }
                    currentY += fontSize + lineSpacing
                }
            }

            WatermarkTheme.CLASSIC_SLATE -> {
                // Style: Frosty/Slate dark rounded rectangle card with crisp white lettering
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#E01A1A1A") // 87% Dark slate grey
                    style = Paint.Style.FILL
                }
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#33FFFFFF") // Subtle transparent white outline
                    style = Paint.Style.STROKE
                    strokeWidth = (shortDim * 0.002f).coerceAtLeast(2f)
                }

                val rect = RectF(startX, startY, startX + boxWidth, startY + boxHeight)
                val rx = shortDim * 0.015f
                canvas.drawRoundRect(rect, rx, rx, bgPaint)
                canvas.drawRoundRect(rect, rx, rx, borderPaint)

                // Draw Text
                paintLabel.color = Color.WHITE
                paintValue.color = Color.WHITE
                var currentY = startY + padding + fontSize * 0.85f

                for ((label, value) in lines) {
                    if (label.isEmpty()) {
                        paintLabel.textSize = fontSize * 1.1f
                        canvas.drawText(value, startX + padding, currentY, paintLabel)
                    } else {
                        val lblText = "【$label】"
                        canvas.drawText(lblText, startX + padding, currentY, paintLabel)
                        val lblWidth = paintLabel.measureText(lblText)
                        canvas.drawText(value, startX + padding + lblWidth, currentY, paintValue)
                    }
                    currentY += fontSize + lineSpacing
                }
            }

            WatermarkTheme.CONSTRUCTION -> {
                // Style: Bright yellow & white text, dark modern layout, safety vertical orange bar
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#F50C0C0C") // Premium dense black card
                }
                val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FFFF9E00") // Action Safety Orange-Yellow
                    style = Paint.Style.FILL
                }

                val rect = RectF(startX, startY, startX + boxWidth, startY + boxHeight)
                canvas.drawRect(rect, bgPaint)

                // Left Accent vertical thick bar
                val barWidth = shortDim * 0.012f
                val barRect = RectF(startX, startY, startX + barWidth, startY + boxHeight)
                canvas.drawRect(barRect, barPaint)

                paintLabel.color = Color.parseColor("#FFFF9E00") // Safety Gold
                paintValue.color = Color.WHITE
                var currentY = startY + padding + fontSize * 0.85f

                for ((label, value) in lines) {
                    if (label.isEmpty()) {
                        paintLabel.color = Color.WHITE
                        paintLabel.textSize = fontSize * 1.15f
                        canvas.drawText(value, startX + padding + barWidth, currentY, paintLabel)
                    } else {
                        val lblText = "【$label】"
                        paintLabel.textSize = fontSize
                        canvas.drawText(lblText, startX + padding + barWidth, currentY, paintLabel)
                        val lblWidth = paintLabel.measureText(lblText)
                        canvas.drawText(value, startX + padding + barWidth + lblWidth, currentY, paintValue)
                    }
                    currentY += fontSize + lineSpacing
                }
            }

            WatermarkTheme.CYBER_TECH -> {
                // Style: glowing matrix cyber brackets and tech-green layouts
                val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FF00FF88") // Neon cyber green
                    style = Paint.Style.STROKE
                    strokeWidth = (shortDim * 0.003f).coerceAtLeast(3f)
                }
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#AA000803") // Very transparent green-tint black
                }

                val rect = RectF(startX, startY, startX + boxWidth, startY + boxHeight)
                canvas.drawRect(rect, bgPaint)

                // Draw bracket corners manually
                val l = shortDim * 0.03f
                // Top-Left tag
                canvas.drawLine(startX, startY, startX + l, startY, framePaint)
                canvas.drawLine(startX, startY, startX, startY + l, framePaint)
                // Top-Right tag
                canvas.drawLine(startX + boxWidth, startY, startX + boxWidth - l, startY, framePaint)
                canvas.drawLine(startX + boxWidth, startY, startX + boxWidth, startY + l, framePaint)
                // Bottom-Left tag
                canvas.drawLine(startX, startY + boxHeight, startX + l, startY + boxHeight, framePaint)
                canvas.drawLine(startX, startY + boxHeight, startX, startY + boxHeight - l, framePaint)
                // Bottom-Right tag
                canvas.drawLine(startX + boxWidth, startY + boxHeight, startX + boxWidth - l, startY + boxHeight, framePaint)
                canvas.drawLine(startX + boxWidth, startY + boxHeight, startX + boxWidth, startY + boxHeight - l, framePaint)

                paintLabel.color = Color.parseColor("#FF00FF88")
                paintValue.color = Color.parseColor("#DD00FF88")
                var currentY = startY + padding + fontSize * 0.85f

                for ((label, value) in lines) {
                    if (label.isEmpty()) {
                        paintLabel.textSize = fontSize * 1.1f
                        canvas.drawText("[ $value ]", startX + padding, currentY, paintLabel)
                    } else {
                        val lblText = "${label.uppercase()}: "
                        canvas.drawText(lblText, startX + padding, currentY, paintLabel)
                        val lblWidth = paintLabel.measureText(lblText)
                        canvas.drawText(value, startX + padding + lblWidth, currentY, paintValue)
                    }
                    currentY += fontSize + lineSpacing
                }
            }

            WatermarkTheme.MINIMAL_BADGE -> {
                // Style: Clean transparent gradient slate with a bright Red / Crimson ink circular stamp
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#C310151E") // Slate blue base
                }
                val rect = RectF(startX, startY, startX + boxWidth, startY + boxHeight)
                val rx = shortDim * 0.02f
                canvas.drawRoundRect(rect, rx, rx, bgPaint)

                // Paint for standard text
                paintLabel.color = Color.WHITE
                paintValue.color = Color.parseColor("#FFE8F0FE")
                var currentY = startY + padding + fontSize * 0.85f

                for ((label, value) in lines) {
                    if (label.isEmpty()) {
                        paintLabel.textSize = fontSize * 1.1f
                        canvas.drawText(value, startX + padding, currentY, paintLabel)
                    } else {
                        val lblText = "▶ $label: "
                        canvas.drawText(lblText, startX + padding, currentY, paintLabel)
                        val lblWidth = paintLabel.measureText(lblText)
                        canvas.drawText(value, startX + padding + lblWidth, currentY, paintValue)
                    }
                    currentY += fontSize + lineSpacing
                }

                // Draw a beautiful ink-stamp seal "VERIFIED /已验证" on top right
                val stampSize = (shortDim * 0.09f).coerceAtLeast(80f)
                val stampX = startX + boxWidth - stampSize - padding * 0.3f
                val stampY = startY + padding * 0.3f

                val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#DDA32323") // Crimson Ink Stamp with slight opacity
                    style = Paint.Style.STROKE
                    strokeWidth = stampSize * 0.04f
                }

                // Tilt action for authentic stamp look
                canvas.save()
                canvas.rotate(-8f, stampX + stampSize / 2, stampY + stampSize / 2)

                // Circle stamp border
                canvas.drawCircle(stampX + stampSize / 2, stampY + stampSize / 2, stampSize / 2, stampPaint)
                canvas.drawCircle(stampX + stampSize / 2, stampY + stampSize / 2, stampSize / 2 - stampPaint.strokeWidth * 1.5f, stampPaint)

                // Center Text inside the circular stamp
                val stampTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#DDA32323")
                    textSize = stampSize * 0.22f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val badgeVal = config.badgeText
                val textW = stampTextPaint.measureText(badgeVal)
                canvas.drawText(
                    badgeVal,
                    stampX + (stampSize - textW) / 2f,
                    stampY + stampSize / 2f + stampTextPaint.textSize * 0.3f,
                    stampTextPaint
                )
                canvas.restore()
            }
        }

        return workingBitmap
    }

    /**
     * Compress photo bitmap and write into system MediaStore (Gallery app sync automatically).
     */
    fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
        val resolver = context.contentResolver
        val filename = "WMC_${System.currentTimeMillis()}.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Suffixes standard Pictures path to isolate screenshots and images
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WatermarkCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (imageUri != null) {
            try {
                resolver.openOutputStream(imageUri)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(imageUri, contentValues, null, null)
                }
                return imageUri
            } catch (e: Exception) {
                e.printStackTrace()
                // Cleanup on failure
                resolver.delete(imageUri, null, null)
            }
        }
        return null
    }
}
