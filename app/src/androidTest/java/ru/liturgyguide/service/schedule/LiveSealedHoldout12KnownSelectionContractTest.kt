package ru.liturgyguide.service.schedule

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pure synthetic index lists only. No sealed asset, selected object, network or source lookup. */
@RunWith(AndroidJUnit4::class)
class LiveSealedHoldout12KnownSelectionContractTest {
    private fun rejected(label: String, block: () -> Unit) {
        try { block(); fail(label) }
        catch (_: IllegalArgumentException) { /* required policy failure */ }
    }

    @Test fun defaultPreservesEveryBatchsFullTwentyAndOriginalOrder() {
        for (batch in 1..5) {
            // Neutral, deliberately unsorted synthetic data; not the actual sealed groups.
            val indices = (0 until 20).map { (it * 7 + batch * 3) % 100 }
            val before = indices.toList()
            assertEquals(20, indices.distinct().size)
            val selected = holdout12SelectedIndices(indices, batch, null)
            assertEquals("batch=$batch", before, selected)
            assertEquals(20, selected.size); assertEquals(before, indices)
        }
    }

    @Test fun explicitKnownSelectionReturnsOnlyTheRequestedExistingIndexForBatchesOneToThree() {
        val indices = listOf(99, 0) + (1..18).toList()
        for (batch in 1..3) for (index in indices) {
            assertEquals("batch=$batch index=$index", listOf(index),
                holdout12SelectedIndices(indices, batch, index.toString()))
            assertEquals(20, indices.size)
        }
    }

    @Test fun futureBatchesInvalidArgumentsAndMalformedBatchesFailWithoutSubsetLookup() {
        val neverRead = object : AbstractList<Int>() {
            override val size: Int get() = error("Future-batch subset must fail before index lookup")
            override fun get(index: Int): Int = error("Future-batch subset must not inspect an index")
        }
        for (batch in 4..5) for (raw in listOf("0", "99", "bad"))
            rejected("future batch=$batch raw=$raw") { holdout12SelectedIndices(neverRead, batch, raw) }
        val indices = (0 until 20).toList()
        val invalid = listOf("", "00", "01", "099", "-1", "+1", "100", " 1", "1 ", "1\n", "1.0", "1,2", "1/2", "١", "１２", "0x1")
        for (raw in invalid) rejected("invalid '$raw'") { holdout12SelectedIndices(indices, 1, raw) }
        for (batch in 1..3) rejected("index not in batch=$batch") { holdout12SelectedIndices(indices, batch, "27") }
        for (batch in listOf(0, 6)) rejected("invalid batch=$batch") { holdout12SelectedIndices(indices, batch, null) }
        val malformed = listOf(indices.dropLast(1), indices + 20, indices.dropLast(1) + 18,
            indices.dropLast(1) + 100, indices.dropLast(1) + -1)
        for ((index, rows) in malformed.withIndex()) for (arg in listOf(null, "0"))
            rejected("malformed batch=$index arg=$arg") { holdout12SelectedIndices(rows, 1, arg) }
    }
}
