package com.example.infra.stub

// Noop implementations matching domain port signatures.
// Infra never imports domain typealiases — Kotlin's structural typing makes
// the assignment valid in the wiring layer (Application.kt).

// TODO(infra-onnx): remove when OnnxInferenceAdapter is wired
fun noopInfer(): suspend (FloatArray) -> FloatArray = { FloatArray(768) }

// TODO(infra-lucene): remove when LuceneAdapter.write is wired
fun noopEmbedStore(): suspend (Long, FloatArray) -> Unit = { _, _ -> }

// TODO(infra-lucene): remove when LuceneAdapter lookup is wired
fun noopEmbedLookup(): suspend (List<Long>) -> Map<Long, FloatArray> = { emptyMap() }

// TODO(infra-lucene): remove when LuceneAdapter.search is wired
fun noopIndexSearch(): suspend (FloatArray, Int) -> List<Long> = { _, _ -> emptyList() }
