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
    suspend fun insert(
        id: String,
        status: String,
        origin: String,
        query: String,
        userId: Long? = null,
    )

    suspend fun updateStatus(
        id: String,
        status: String,
        completedAt: Long? = null,
        errorMessage: String? = null,
    )

    suspend fun findById(id: String): AcquisitionJob?
}

interface CatalogItemPort {
    suspend fun insertIdempotent(
        md5: String,
        url: String,
        origin: String,
        rating: String?,
        embeddingVersion: String,
        indexedAt: Long,
    ): Pair<Long, Boolean>

    suspend fun countAll(): Long

    suspend fun loadSample(limit: Int): List<Long>
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
    suspend fun seedIfMissing(now: Long)

    suspend fun read(): GlobalSystemState

    suspend fun setDefaultEmbeddingVersion(
        version: String,
        now: Long,
    )

    suspend fun activateCluster(
        clusterId: Long,
        now: Long,
    )

    suspend fun activateIndex(
        indexId: Long,
        now: Long,
    )

    suspend fun setCounts(
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
    suspend fun createBuilding(
        embeddingVersion: String,
        clusterCount: Int,
        catalogSizeAtBuild: Long,
        centroidsPath: String,
    ): Long

    suspend fun findById(id: Long): ClusterGeneration?
}
