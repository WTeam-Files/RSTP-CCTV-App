package com.zektopic.cctvapp

object TimestampOverlay {

    const val MARGIN_PERCENT = 2f

    data class Geometry(
        val scaleX: Float,
        val scaleY: Float,
        val posX: Float,
        val posY: Float
    )

    fun scaleForSize(size: String): Pair<Float, Float> = scaleForSize(size, 0, 0)

    fun scaleForSize(size: String, streamWidth: Int, streamHeight: Int): Pair<Float, Float> {
        val (baseX, baseY) = when (size) {
            "Small" -> Pair(25f, 6f)
            "Large" -> Pair(45f, 14f)
            else -> Pair(35f, 10f)
        }
        if (streamWidth > 0 && streamHeight > streamWidth) {
            val ratio = streamHeight.toFloat() / streamWidth
            return Pair(
                (baseX * ratio).coerceAtMost(100f - 2 * MARGIN_PERCENT),
                baseY / ratio
            )
        }
        return Pair(baseX, baseY)
    }

    fun positionFor(
        position: String,
        scaleX: Float,
        scaleY: Float,
        margin: Float = MARGIN_PERCENT
    ): Pair<Float, Float> {
        val leftX = margin
        val rightX = 100f - scaleX - margin
        val topY = margin
        val bottomY = 100f - scaleY - margin
        return when (position) {
            "Top Right" -> Pair(rightX, topY)
            "Bottom Left" -> Pair(leftX, bottomY)
            "Bottom Right" -> Pair(rightX, bottomY)
            else -> Pair(leftX, topY)
        }
    }

    fun geometryFor(
        position: String,
        size: String,
        margin: Float = MARGIN_PERCENT
    ): Geometry = geometryFor(position, size, 0, 0, margin)

    fun geometryFor(
        position: String,
        size: String,
        streamWidth: Int,
        streamHeight: Int,
        margin: Float = MARGIN_PERCENT
    ): Geometry {
        val (scaleX, scaleY) = scaleForSize(size, streamWidth, streamHeight)
        val (posX, posY) = positionFor(position, scaleX, scaleY, margin)
        return Geometry(scaleX, scaleY, posX, posY)
    }

    fun effectiveResolution(width: Int, height: Int, rotation: Int): Pair<Int, Int> =
        if (rotation == 90 || rotation == 270) Pair(height, width) else Pair(width, height)
}
