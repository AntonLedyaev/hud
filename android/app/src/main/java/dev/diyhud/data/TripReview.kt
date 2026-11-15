package dev.diyhud.data

import dev.diyhud.domain.DomainEvent

data class ReviewedEvent(val record: EventEntity, val event: DomainEvent, val sources: List<DomainEvent>)
data class TripReview(val replay: RecordedReplay, val header: SessionHeader, val samples: List<SampleEntity>)
data class DeleteResult(val deleted: Boolean, val message: String? = null)
