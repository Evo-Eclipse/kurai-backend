package com.example.infrastructure.lucene

import com.example.domain.catalog.ItemVectorIndexPort
import org.apache.lucene.codecs.KnnVectorsFormat
import org.apache.lucene.codecs.lucene104.Lucene104Codec
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat
import org.apache.lucene.document.Document
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.LongPoint
import org.apache.lucene.document.StoredField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.KnnFloatVectorQuery
import org.apache.lucene.store.FSDirectory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lucene HNSW adapter for storing and searching item embedding vectors.
 *
 * Each `LuceneAdapter` instance owns one segment directory under
 * [indexPath]; the directory layout (one dir per `embedding_version`) is
 * managed by [IndexGenerationManager].
 *
 * Vector layout: 768-dim float32, COSINE similarity. All input vectors must
 * be L2-normalized before [write] (debug-asserted, see I-4).
 *
 * HNSW hyperparameters per `.specs/SPEC.md §5`:
 * - M = 16 (graph connectivity)
 * - efConstruction = 100 (build-time candidate list size)
 * - efSearch is expressed via the `k` argument of [search] — Lucene's
 *   public API folds top-k and efSearch together; callers needing extra
 *   candidates should over-fetch and truncate downstream.
 */
class LuceneAdapter(
    val indexPath: Path,
) : ItemVectorIndexPort,
    AutoCloseable {
    private val directory: FSDirectory = FSDirectory.open(indexPath)
    private val writer: IndexWriter

    /**
     * Wrapped in [AtomicReference] so [search] can swap to a fresh reader
     * after [refresh] without blocking concurrent searches.
     */
    private val readerRef: AtomicReference<DirectoryReader>

    init {
        val codec =
            object : Lucene104Codec() {
                override fun getKnnVectorsFormatForField(field: String): KnnVectorsFormat =
                    Lucene99HnswVectorsFormat(HNSW_M, HNSW_EF_CONSTRUCTION)
            }
        val cfg =
            IndexWriterConfig().apply {
                setCodec(codec)
                setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
            }
        writer = IndexWriter(directory, cfg)
        // Ensure the index has at least one commit so DirectoryReader.open
        // succeeds on a fresh, never-written index.
        writer.commit()
        readerRef = AtomicReference(DirectoryReader.open(directory))
    }

    /**
     * Adds [vector] for [itemId] to the index. The vector must be 768-dim
     * and L2-normalized; both are debug-asserted.
     *
     * Caller must invoke [refresh] before [search] sees the new doc.
     */
    override fun write(
        itemId: Long,
        vector: FloatArray,
    ) {
        require(vector.size == VECTOR_DIM) {
            "Vector dim must be $VECTOR_DIM, got ${vector.size}"
        }
        assertNormalized(vector)
        val doc =
            Document().apply {
                add(StoredField(FIELD_ITEM_ID, itemId))
                add(LongPoint(FIELD_ITEM_ID_INDEX, itemId))
                add(KnnFloatVectorField(FIELD_VECTOR, vector, VectorSimilarityFunction.COSINE))
            }
        writer.addDocument(doc)
    }

    /**
     * Commits pending writes and refreshes the reader so subsequent
     * [search] calls see them.
     */
    override fun refresh() {
        writer.commit()
        val current = readerRef.get()
        val refreshed = DirectoryReader.openIfChanged(current) ?: return
        readerRef.set(refreshed)
        current.close()
    }

    /** Number of indexed (embedded) documents in the current reader view. */
    override fun numDocs(): Int = readerRef.get().numDocs()

    /**
     * Returns up to [k] nearest item ids by cosine similarity. Empty index
     * returns an empty list. Concurrent-safe with [refresh].
     */
    fun search(
        query: FloatArray,
        k: Int,
    ): List<Long> {
        require(query.size == VECTOR_DIM) {
            "Query vector dim must be $VECTOR_DIM, got ${query.size}"
        }
        require(k > 0) { "k must be positive, got $k" }
        assertNormalized(query)

        val reader = readerRef.get()
        if (reader.numDocs() == 0) return emptyList()

        val searcher = IndexSearcher(reader)
        val knnQuery = KnnFloatVectorQuery(FIELD_VECTOR, query, k)
        val hits = searcher.search(knnQuery, k)

        val storedFields = reader.storedFields()
        return hits.scoreDocs.map { scoreDoc ->
            storedFields
                .document(scoreDoc.doc)
                .getField(FIELD_ITEM_ID)
                .numericValue()
                .toLong()
        }
    }

    override fun getVector(itemId: Long): FloatArray? {
        val reader = readerRef.get()
        if (reader.numDocs() == 0) return null
        val searcher = IndexSearcher(reader)
        val hits = searcher.search(LongPoint.newExactQuery(FIELD_ITEM_ID_INDEX, itemId), 1)
        if (hits.scoreDocs.isEmpty()) return null
        val globalDocId = hits.scoreDocs[0].doc
        for (ctx in reader.leaves()) {
            if (globalDocId < ctx.docBase || globalDocId >= ctx.docBase + ctx.reader().maxDoc()) continue
            val localDocId = globalDocId - ctx.docBase
            val vv = ctx.reader().getFloatVectorValues(FIELD_VECTOR) ?: return null
            val iter = vv.iterator()
            if (iter.advance(localDocId) != localDocId) return null
            return vv.vectorValue(iter.index()).copyOf()
        }
        return null
    }

    override fun close() {
        writer.close()
        readerRef.get()?.close()
        directory.close()
    }

    private fun assertNormalized(vector: FloatArray) {
        // I-4 debug-assert: ‖v‖ ≈ 1 within tolerance. Skipped in production
        // builds without `-ea`; tests run with assertions enabled by default.
        assert(isNormalized(vector)) {
            "Vector must be L2-normalized (‖v‖≈1), got ‖v‖=${l2Norm(vector)}"
        }
    }

    companion object {
        const val VECTOR_DIM: Int = 768
        const val HNSW_M: Int = 16
        const val HNSW_EF_CONSTRUCTION: Int = 100
        const val L2_TOLERANCE: Float = 1e-3f
        private const val FIELD_ITEM_ID = "item_id"
        private const val FIELD_ITEM_ID_INDEX = "item_id_idx"
        private const val FIELD_VECTOR = "vector"

        private fun l2Norm(v: FloatArray): Float {
            var sum = 0.0
            for (x in v) sum += x * x
            return sqrt(sum).toFloat()
        }

        private fun isNormalized(v: FloatArray): Boolean = abs(l2Norm(v) - 1f) < L2_TOLERANCE
    }
}
