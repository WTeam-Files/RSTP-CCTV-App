package com.zektopic.cctvapp

object TimestampOverlay {

    const val MARGIN_PERCENT = 2f

    data class Geometry(
        val scaleX: Float,
        val scaleY: Float,
        val posX: Float,
        val posY: Float
    )

    fun scaleForSize(size: String): Pair<Float, Float> = when (size) {
        "Small" -> Pair(25f, 6f)
        "Large" -> Pair(45f, 14f)
        else -> Pair(35f, 10f)
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
    ): Geometry {
        val (scaleX, scaleY) = scaleForSize(size)
        val (posX, posY) = positionFor(position, scaleX, scaleY, margin)
        return Geometry(scaleX, scaleY, posX, posY)
    }
}
