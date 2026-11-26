package com.thedirepack.constructioncalc.feature.drawings.recognition

import android.content.Context

/** Pixels of one rendered tile: ARGB, row-major, [width] × [height]. */
class TileImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height)
    }
}

/** A box in a tile's pixels. */
data class PixelBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
    }
}

/** One raw detector output: its own class name (snapped to the catalog later) and confidence. */
data class RawDetection(val classId: String, val confidence: Float, val box: PixelBox)

/**
 * How a detector wants pages fed to it. Pages are rendered at [dpi] and cut into [tileSizePx]
 * tiles overlapping by [overlapPx], so every symbol is seen whole in at least one tile at the
 * scale the model was trained on (the training pipeline renders at 200 DPI into 512 px tiles with
 * 160 px overlap). Detections under [minConfidence] are dropped.
 */
data class DetectorConfig(
    val dpi: Float = 200f,
    val tileSizePx: Int = 512,
    val overlapPx: Int = 160,
    val minConfidence: Float = 0.25f,
) {
    init {
        require(dpi in 36f..1200f && tileSizePx in 64..4096 && overlapPx in 0 until tileSizePx && minConfidence in 0f..1f)
    }
}

sealed interface DetectorStatus {
    /** No model on this device; recognition can't run. */
    data object NotInstalled : DetectorStatus
    data class Ready(val name: String) : DetectorStatus
}

/**
 * An on-device symbol detector. Implementations must be safe to call from a background thread;
 * [detect] may block for the length of one inference.
 */
interface SymbolDetector {
    val status: DetectorStatus
    val config: DetectorConfig
    fun detect(tile: TileImage): List<RawDetection>
}

/** The default until a model ships: says plainly that recognition isn't available. */
object NotInstalledDetector : SymbolDetector {
    override val status: DetectorStatus = DetectorStatus.NotInstalled
    override val config: DetectorConfig = DetectorConfig()
    override fun detect(tile: TileImage): List<RawDetection> =
        throw IllegalStateException("The symbol recognition model isn't installed.")
}

object SymbolDetectors {
    /**
     * The detector this install has. The model is trained separately (ml/symbol-detector) and is
     * not bundled yet, so this is always [NotInstalledDetector] for now.
     */
    @Suppress("UNUSED_PARAMETER")
    fun installed(context: Context): SymbolDetector = NotInstalledDetector
}
