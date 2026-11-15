package dev.diyhud.session

import org.junit.Assert.*
import org.junit.Test

class EvidenceAccessTest {
    @Test fun recognitionAndReviewExcludeEachOtherAcrossOwners() {
        val access = EvidenceAccess(); val camera = Any(); val review = Any()
        assertTrue(access.start(camera)); assertFalse(access.open(review))
        access.stop(review); assertFalse(access.open(review))
        access.stop(camera); assertTrue(access.open(review)); assertFalse(access.start(camera))
        access.close(review); assertTrue(access.start(camera))
    }
    @Test fun finishingExportBlocksRecognitionAfterActivityCloses() {
        val access = EvidenceAccess(); val old = Any(); val replacement = Any()
        assertTrue(access.open(old))
        val operation = access.operation(old)
        access.close(old); assertFalse(access.allowed(old)); assertFalse(access.start(replacement))
        operation.close(); operation.close()
        assertTrue(access.start(replacement))
        assertThrows(IllegalStateException::class.java) { access.operation(old) }
    }
}
