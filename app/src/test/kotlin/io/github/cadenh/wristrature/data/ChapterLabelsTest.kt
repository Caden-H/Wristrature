package io.github.cadenh.wristrature.data

import io.github.cadenh.wristrature.core.book.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterLabelsTest {
    @Test fun numberedChaptersAreShortened() {
        assertEquals("Chapter I", ChapterLabels.short("CHAPTER I. Down the Rabbit-Hole"))
        assertEquals("Chapter 12", ChapterLabels.short("Chapter 12: Letters"))
        assertEquals("Part IV", ChapterLabels.short("PART IV"))
    }

    @Test fun otherTitlesAreCutToFit() {
        assertEquals("Preface", ChapterLabels.short("Preface"))
        assertEquals("A very long chapt…", ChapterLabels.short("A very long chapter title that does not fit"))
    }

    @Test fun placeFindsTheLastEntryAtOrBefore() {
        val toc = listOf(TocEntry("One", 0, 1, 0), TocEntry("Two", 0, 3, 5), TocEntry("Three", 0, 6, 0))
        assertNull(ChapterLabels.entryAt(toc, 0, 0))
        assertEquals("One", ChapterLabels.entryAt(toc, 2, 9)?.title)
        assertEquals("Two", ChapterLabels.entryAt(toc, 3, 5)?.title)
        assertEquals("Two", ChapterLabels.entryAt(toc, 5, 0)?.title)
        assertEquals("Three", ChapterLabels.entryAt(toc, 9, 0)?.title)
    }
}
