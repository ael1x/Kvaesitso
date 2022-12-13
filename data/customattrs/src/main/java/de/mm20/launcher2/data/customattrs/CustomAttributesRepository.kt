package de.mm20.launcher2.data.customattrs

import de.mm20.launcher2.crashreporter.CrashReporter
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.database.entities.CustomAttributeEntity
import de.mm20.launcher2.favorites.FavoritesRepository
import de.mm20.launcher2.ktx.jsonObjectOf
import de.mm20.launcher2.search.SavableSearchable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import java.io.File

interface CustomAttributesRepository {

    fun search(query: String): Flow<ImmutableList<SavableSearchable>>

    fun getCustomIcon(searchable: SavableSearchable): Flow<CustomIcon?>
    fun setCustomIcon(searchable: SavableSearchable, icon: CustomIcon?)

    fun getCustomLabels(items: List<SavableSearchable>): Flow<List<CustomLabel>>
    fun setCustomLabel(searchable: SavableSearchable, label: String)
    fun clearCustomLabel(searchable: SavableSearchable)

    fun setTags(searchable: SavableSearchable, tags: List<String>)
    fun getTags(searchable: SavableSearchable): Flow<List<String>>

    suspend fun export(toDir: File)
    suspend fun import(fromDir: File)

    suspend fun getAllTags(startsWith: String? = null): List<String>
    fun getItemsForTag(tag: String): Flow<List<SavableSearchable>>
    fun addTag(item: SavableSearchable, tag: String)

    fun renameTag(oldName: String, newName: String)
    suspend fun cleanupDatabase(): Int
}

internal class CustomAttributesRepositoryImpl(
    private val appDatabase: AppDatabase,
    private val favoritesRepository: FavoritesRepository
) : CustomAttributesRepository {
    private val scope = CoroutineScope(Job() + Dispatchers.Default)

    override fun getCustomIcon(searchable: SavableSearchable): Flow<CustomIcon?> {
        val dao = appDatabase.customAttrsDao()
        return dao.getCustomAttribute(searchable.key, CustomAttributeType.Icon.value)
            .map {
                CustomAttribute.fromDatabaseEntity(it) as? CustomIcon
            }
    }

    override fun setCustomIcon(searchable: SavableSearchable, icon: CustomIcon?) {
        val dao = appDatabase.customAttrsDao()
        scope.launch {
            dao.clearCustomAttribute(searchable.key, CustomAttributeType.Icon.value)
            if (icon != null) {
                dao.setCustomAttribute(icon.toDatabaseEntity(searchable.key))
            }
        }
    }

    override fun getCustomLabels(items: List<SavableSearchable>): Flow<List<CustomLabel>> {
        val dao = appDatabase.customAttrsDao()
        return dao.getCustomAttributes(items.map { it.key }, CustomAttributeType.Label.value)
            .map { list ->
                list.mapNotNull { CustomAttribute.fromDatabaseEntity(it) as? CustomLabel }
            }
    }

    override fun setCustomLabel(searchable: SavableSearchable, label: String) {
        val dao = appDatabase.customAttrsDao()
        scope.launch {
            favoritesRepository.save(searchable)
            appDatabase.runInTransaction {
                dao.clearCustomAttribute(searchable.key, CustomAttributeType.Label.value)
                dao.setCustomAttribute(
                    CustomLabel(
                        key = searchable.key,
                        label = label,
                    ).toDatabaseEntity(searchable.key)
                )
            }
        }
    }

    override fun clearCustomLabel(searchable: SavableSearchable) {
        val dao = appDatabase.customAttrsDao()
        scope.launch {
            dao.clearCustomAttribute(searchable.key, CustomAttributeType.Label.value)
        }
    }

    override fun setTags(searchable: SavableSearchable, tags: List<String>) {
        val dao = appDatabase.customAttrsDao()
        scope.launch {
            favoritesRepository.save(searchable)
            dao.setTags(searchable.key, tags.map {
                CustomTag(it).toDatabaseEntity(searchable.key)
            })
        }
    }

    override fun getTags(searchable: SavableSearchable): Flow<List<String>> {
        val dao = appDatabase.customAttrsDao()
        return dao.getCustomAttributes(listOf(searchable.key), CustomAttributeType.Tag.value).map {
            it.map { it.value }
        }
    }

    override suspend fun getAllTags(startsWith: String?): List<String> {
        val dao = appDatabase.customAttrsDao()
        return if (startsWith != null) {
            dao.getAllTagsLike("$startsWith%")
        } else {
            dao.getAllTags()
        }
    }

    override fun getItemsForTag(tag: String): Flow<List<SavableSearchable>> {
        val dao = appDatabase.customAttrsDao()
        return dao.getItemsWithTag(tag).map {
            favoritesRepository.getFromKeys(it)
        }
    }

    override fun addTag(item: SavableSearchable, tag: String) {
        val dao = appDatabase.customAttrsDao()
        scope.launch {
            dao.addTag(item.key, tag)
        }
    }

    override fun renameTag(oldName: String, newName: String) {
        val dao = appDatabase.customAttrsDao()
        scope.launch {
            dao.renameTag(oldName, newName)
        }
    }

    override fun search(query: String): Flow<ImmutableList<SavableSearchable>> {
        if (query.isBlank()) {
            return flow {
                emit(persistentListOf())
            }
        }
        val dao = appDatabase.customAttrsDao()
        return dao.search("%$query%").map {
            favoritesRepository.getFromKeys(it).toImmutableList()
        }
    }

    override suspend fun export(toDir: File) = withContext(Dispatchers.IO) {
        val dao = appDatabase.backupDao()
        var page = 0
        do {
            val customAttrs = dao.exportCustomAttributes(limit = 100, offset = page * 100)
            val jsonArray = JSONArray()
            for (customAttr in customAttrs) {
                jsonArray.put(
                    jsonObjectOf(
                        "key" to customAttr.key,
                        "value" to customAttr.value,
                        "type" to customAttr.type,
                    )
                )
            }

            val file = File(toDir, "customizations.${page.toString().padStart(4, '0')}")
            file.bufferedWriter().use {
                it.write(jsonArray.toString())
            }
            page++
        } while (customAttrs.size == 100)
    }

    override suspend fun import(fromDir: File) = withContext(Dispatchers.IO) {
        val dao = appDatabase.backupDao()
        dao.wipeCustomAttributes()

        val files =
            fromDir.listFiles { _, name -> name.startsWith("customizations.") }
                ?: return@withContext

        for (file in files) {
            val customAttrs = mutableListOf<CustomAttributeEntity>()
            try {
                val jsonArray = JSONArray(file.inputStream().reader().readText())

                for (i in 0 until jsonArray.length()) {
                    val json = jsonArray.getJSONObject(i)

                    val entity = CustomAttributeEntity(
                        id = null,
                        type = json.getString("type"),
                        value = json.optString("value"),
                        key = json.optString("key"),
                    )
                    customAttrs.add(entity)
                }

                dao.importCustomAttributes(customAttrs)

            } catch (e: JSONException) {
                CrashReporter.logException(e)
            }
        }
    }

    override suspend fun cleanupDatabase(): Int {
        val dao = appDatabase.backupDao()
        var removed = 0
        val job = scope.launch {
            removed = dao.cleanUp()
        }
        job.join()
        return removed
    }
}