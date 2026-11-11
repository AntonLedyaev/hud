package dev.diyhud.inference

import dev.diyhud.domain.Detection

fun decodeYolo(output: FloatArray, contract: ModelContract, geometry: Letterbox): List<Detection> {
    val count = contract.output.tensor.shape[2]
    val classes = contract.labels.classes.size
    require(output.size == (classes + 4) * count)
    val candidates = (0 until count).mapNotNull { index ->
        val best = (0 until classes).maxBy { output[(it + 4) * count + index] }
        val score = output[(best + 4) * count + index].toDouble()
        if (!score.isFinite() || score !in contract.output.confidenceThreshold..1.0) return@mapNotNull null
        val box = geometry.sourceBox(output[index].toDouble(), output[count + index].toDouble(),
            output[2 * count + index].toDouble(), output[3 * count + index].toDouble())
        box?.let { Detection(contract.labels.classes[best].id, score, it) }
    }.sortedByDescending { it.confidence }
    val kept = mutableListOf<Detection>()
    for (candidate in candidates) {
        if (kept.none { it.classId == candidate.classId &&
                it.box.iou(candidate.box) > contract.output.nmsIou }) kept += candidate
    }
    return kept.toList()
}
