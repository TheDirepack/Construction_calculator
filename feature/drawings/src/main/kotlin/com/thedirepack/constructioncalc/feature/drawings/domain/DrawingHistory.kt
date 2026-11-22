package com.thedirepack.constructioncalc.feature.drawings.domain

/**
 * Pure immutable undo/redo history stack for drawing edits (markups, measurements, zones, calibration).
 */
data class DrawingHistory<T>(
    val undoStack: List<T> = emptyList(),
    val redoStack: List<T> = emptyList(),
    val maxEntries: Int = 50,
) {
    init {
        require(maxEntries >= 1) { "maxEntries must be at least 1" }
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Records [previous] before applying a new edit, clearing any redo branch. */
    fun push(previous: T): DrawingHistory<T> {
        val nextUndo = if (undoStack.size >= maxEntries) {
            undoStack.drop(undoStack.size - maxEntries + 1) + previous
        } else {
            undoStack + previous
        }
        return copy(undoStack = nextUndo, redoStack = emptyList())
    }

    /** Steps back to the previous snapshot, pushing [current] onto the redo stack. */
    fun undo(current: T): Pair<T, DrawingHistory<T>>? {
        if (undoStack.isEmpty()) return null
        val restored = undoStack.last()
        val nextRedo = if (redoStack.size >= maxEntries) {
            redoStack.drop(redoStack.size - maxEntries + 1) + current
        } else {
            redoStack + current
        }
        return restored to copy(undoStack = undoStack.dropLast(1), redoStack = nextRedo)
    }

    /** Re-applies the last undone snapshot, pushing [current] onto the undo stack. */
    fun redo(current: T): Pair<T, DrawingHistory<T>>? {
        if (redoStack.isEmpty()) return null
        val restored = redoStack.last()
        val nextUndo = if (undoStack.size >= maxEntries) {
            undoStack.drop(undoStack.size - maxEntries + 1) + current
        } else {
            undoStack + current
        }
        return restored to copy(undoStack = nextUndo, redoStack = redoStack.dropLast(1))
    }

    fun clear(): DrawingHistory<T> = copy(undoStack = emptyList(), redoStack = emptyList())
}
