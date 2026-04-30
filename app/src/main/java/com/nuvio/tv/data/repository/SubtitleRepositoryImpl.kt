package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.repository.SubtitleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class SubtitleRepositoryImpl @Inject constructor(
    private val api: AddonApi,
    private val addonRepository: AddonRepositoryImpl
) : SubtitleRepository {

    companion object {
        private const val TAG = "SubtitleRepository"
        private const val PER_ADDON_TIMEOUT_MS = 20_000L
    }

    override suspend fun getSubtitles(
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)?
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val requestType = canonicalSubtitleType(type)
        val startedAtMs = System.currentTimeMillis()
        Log.d(TAG, "Fetching subtitles for type=$requestType, id=$id, videoId=$videoId")
        val subtitleAddons = getSubtitleAddons(requestType, id) ?: return@withContext emptyList()
        
        Log.d(TAG, "Found ${subtitleAddons.size} subtitle addons: ${subtitleAddons.map { it.name }}")

        if (subtitleAddons.isEmpty()) {
            return@withContext emptyList()
        }

        val result = fetchAllAddonSubtitles(
            addons = subtitleAddons,
            type = type,
            id = id,
            videoId = videoId,
            videoHash = videoHash,
            videoSize = videoSize,
            filename = filename,
            onProgress = onProgress
        )
        Log.d(
            TAG,
            "Subtitle fetch completed total=${result.size} fromAddons=${subtitleAddons.size} in ${System.currentTimeMillis() - startedAtMs}ms"
        )
        result
    }

    override suspend fun getFirstSubtitles(
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)?,
        acceptSubtitle: ((Subtitle) -> Boolean)?
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val requestType = canonicalSubtitleType(type)
        val startedAtMs = System.currentTimeMillis()
        Log.d(TAG, "Fetching first subtitles for type=$requestType, id=$id, videoId=$videoId")
        val subtitleAddons = getSubtitleAddons(requestType, id) ?: return@withContext emptyList()

        Log.d(TAG, "Found ${subtitleAddons.size} subtitle addons: ${subtitleAddons.map { it.name }}")

        if (subtitleAddons.isEmpty()) {
            return@withContext emptyList()
        }

        val result = fetchFirstAddonSubtitles(
            addons = subtitleAddons,
            type = type,
            id = id,
            videoId = videoId,
            videoHash = videoHash,
            videoSize = videoSize,
            filename = filename,
            onProgress = onProgress,
            acceptSubtitle = acceptSubtitle
        )
        Log.d(
            TAG,
            "First subtitle fetch completed total=${result.size} fromAddons=${subtitleAddons.size} in ${System.currentTimeMillis() - startedAtMs}ms"
        )
        result
    }

    private suspend fun getSubtitleAddons(
        requestType: String,
        id: String
    ): List<Addon>? {
        val addons = try {
            addonRepository.getInstalledAddons().first()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get installed addons", e)
            return null
        }

        return addons.filter { addon ->
            addon.resources.any { resource ->
                isSubtitleResource(resource.name) && supportsType(resource, requestType, id)
            }
        }
    }

    private suspend fun fetchAllAddonSubtitles(
        addons: List<Addon>,
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)?
    ): List<Subtitle> {
        val total = addons.size
        val completedCount = AtomicInteger(0)
        onProgress?.invoke(0, total, null)

        return coroutineScope {
            addons.map { addon ->
                async {
                    fetchAddonWithProgress(
                        addon = addon,
                        type = type,
                        id = id,
                        videoId = videoId,
                        videoHash = videoHash,
                        videoSize = videoSize,
                        filename = filename,
                        completedCount = completedCount,
                        total = total,
                        onProgress = onProgress
                    )
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun fetchFirstAddonSubtitles(
        addons: List<Addon>,
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)?,
        acceptSubtitle: ((Subtitle) -> Boolean)?
    ): List<Subtitle> = coroutineScope {
        val total = addons.size
        val completedCount = AtomicInteger(0)
        onProgress?.invoke(0, total, null)

        val pending = addons.map { addon ->
            async {
                fetchAddonWithProgress(
                    addon = addon,
                    type = type,
                    id = id,
                    videoId = videoId,
                    videoHash = videoHash,
                    videoSize = videoSize,
                    filename = filename,
                    completedCount = completedCount,
                    total = total,
                    onProgress = onProgress
                )
            }
        }.toMutableList()

        while (pending.isNotEmpty()) {
            val (deferred, subtitles) = select<Pair<Deferred<List<Subtitle>>, List<Subtitle>>> {
                pending.forEach { deferred ->
                    deferred.onAwait { subtitles -> deferred to subtitles }
                }
            }
            pending.remove(deferred)
            val acceptedSubtitles = if (acceptSubtitle == null) {
                subtitles
            } else {
                subtitles.filter(acceptSubtitle)
            }
            if (acceptedSubtitles.isNotEmpty()) {
                pending.forEach { it.cancel() }
                return@coroutineScope acceptedSubtitles
            }
        }

        emptyList()
    }

    private suspend fun fetchAddonWithProgress(
        addon: Addon,
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        completedCount: AtomicInteger,
        total: Int,
        onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)?
    ): List<Subtitle> {
        val addonStartMs = System.currentTimeMillis()
        val subtitles = withTimeoutOrNull(PER_ADDON_TIMEOUT_MS) {
            fetchSubtitlesFromAddon(addon, type, id, videoId, videoHash, videoSize, filename)
        }
        onProgress?.invoke(completedCount.incrementAndGet(), total, addon.displayName)
        if (subtitles == null) {
            Log.w(
                TAG,
                "Subtitle fetch timed out for addon=${addon.name} after ${PER_ADDON_TIMEOUT_MS}ms"
            )
            return emptyList()
        }
        Log.d(
            TAG,
            "Subtitle fetch done for addon=${addon.name} count=${subtitles.size} in ${System.currentTimeMillis() - addonStartMs}ms"
        )
        return subtitles
    }

    private fun canonicalSubtitleType(type: String): String {
        return if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()
    }
    
    private fun supportsType(resource: com.nuvio.tv.domain.model.AddonResource, type: String, id: String): Boolean {
        // Check if type is supported
        if (resource.types.isNotEmpty() && resource.types.none { it.equals(type, ignoreCase = true) }) {
            return false
        }
        
        // Check if id prefix is supported
        val idPrefixes = resource.idPrefixes
        if (idPrefixes != null && idPrefixes.isNotEmpty()) {
            return idPrefixes.any { prefix -> id.startsWith(prefix) }
        }
        
        return true
    }

    private fun isSubtitleResource(name: String): Boolean {
        return name.equals("subtitles", ignoreCase = true) ||
            name.equals("subtitle", ignoreCase = true)
    }
    
    private suspend fun fetchSubtitlesFromAddon(
        addon: Addon,
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?
    ): List<Subtitle> {
        val normalizedType = canonicalSubtitleType(type)
        val actualId = if (normalizedType == "series" && videoId != null) {
            // For series, use videoId which includes season/episode
            videoId
        } else {
            id
        }
        
        // Build the subtitle URL with optional extra parameters
        val rawBaseUrl = addon.baseUrl.trimEnd('/')
        val queryStart = rawBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) rawBaseUrl.substring(0, queryStart).trimEnd('/') else rawBaseUrl
        val baseQuery = if (queryStart >= 0) rawBaseUrl.substring(queryStart) else ""
        val extraParams = buildExtraParams(videoHash, videoSize, filename)
        val subtitleUrl = if (extraParams.isNotEmpty()) {
            "$basePath/subtitles/$normalizedType/$actualId/$extraParams.json$baseQuery"
        } else {
            "$basePath/subtitles/$normalizedType/$actualId.json$baseQuery"
        }
        
        Log.d(TAG, "Fetching subtitles from ${addon.name}: $subtitleUrl")
        
        return try {
            when (val result = safeApiCall { api.getSubtitles(subtitleUrl) }) {
                is NetworkResult.Success -> {
                    val subtitles = result.data.subtitles?.mapNotNull { dto ->
                        Subtitle(
                            id = dto.id ?: "${dto.lang}-${dto.url.hashCode()}",
                            url = dto.url,
                            lang = dto.lang,
                            addonName = addon.displayName,
                            addonLogo = addon.logo
                        )
                    } ?: emptyList()
                    
                    Log.d(TAG, "Got ${subtitles.size} subtitles from ${addon.name}")
                    subtitles
                }
                is NetworkResult.Error -> {
                    Log.e(TAG, "Failed to fetch subtitles from ${addon.name}: ${result.message}")
                    emptyList()
                }
                NetworkResult.Loading -> emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching subtitles from ${addon.name}", e)
            emptyList()
        }
    }
    
    private fun buildExtraParams(
        videoHash: String?,
        videoSize: Long?,
        filename: String?
    ): String {
        val params = mutableListOf<String>()
        
        videoHash?.let { params.add("videoHash=$it") }
        videoSize?.let { params.add("videoSize=$it") }
        filename?.let { params.add("filename=$it") }
        
        return if (params.isNotEmpty()) {
            params.joinToString("&")
        } else {
            ""
        }
    }
}
