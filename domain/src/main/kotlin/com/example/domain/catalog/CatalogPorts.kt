package com.example.domain.catalog

data class AcquisitionJob(
    val id: String,
    val status: String,
    val origin: String,
    val query: String,
    val userId: Long?,
    val createdAt: Long,
    val completedAt: Long?,
    val errorMessage: String?,
)

interface AcquisitionJobPort {
    fun insert(
        id: String,
        status: String,
        origin: String,
        query: String,
        userId: Long? = null,
    )

    fun updateStatus(
        id: String,
        status: String,
        completedAt: Long? = null,
        errorMessage: String? = null,
    )

    fun findById(id: String): AcquisitionJob?
}

interface CatalogItemPort {
    fun insertIdempotent(
        md5: String,
        url: String,
        origin: String,
        rating: String?,
        embeddingVersion: String,
        indexedAt: Long,
    ): Pair<Long, Boolean>

    fun countAll(): Long

    fun loadSample(limit: Int): List<Long>
}

interface ItemVectorIndexPort {
    fun getVector(itemId: Long): FloatArray?

    fun numDocs(): Int

    fun write(
        itemId: Long,
        vector: FloatArray,
    )

    fun refresh()
}

data class GlobalSystemState(
    val defaultEmbeddingVersion: String?,
    val activeClusterId: Long?,
    val activeIndexId: Long?,
    val totalItems: Long,
    val embeddedItems: Long,
    val updatedAt: Long,
)

interface SystemStatePort {
    fun seedIfMissing(now: Long)

    fun read(): GlobalSystemState

    fun setDefaultEmbeddingVersion(
        version: String,
        now: Long,
    )

    fun activateCluster(
        clusterId: Long,
        now: Long,
    )

    fun activateIndex(
        indexId: Long,
        now: Long,
    )

    fun setCounts(
        totalItems: Long,
        embeddedItems: Long,
        now: Long,
    )
}

data class ClusterGeneration(
    val id: Long,
    val embeddingVersion: String,
    val status: String,
    val clusterCount: Int,
    val catalogSizeAtBuild: Long,
    val centroidsPath: String,
    val activatedAt: Long?,
)

interface ClusterGenerationPort {
    fun createBuilding(
        embeddingVersion: String,
        clusterCount: Int,
        catalogSizeAtBuild: Long,
        centroidsPath: String,
    ): Long

    fun findById(id: Long): ClusterGeneration?
}
