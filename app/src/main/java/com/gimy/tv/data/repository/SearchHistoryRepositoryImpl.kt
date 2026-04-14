package com.gimy.tv.data.repository

import com.gimy.tv.data.local.dao.SearchHistoryDao
import com.gimy.tv.data.local.entity.SearchHistoryEntity
import com.gimy.tv.domain.repository.SearchHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchHistoryRepositoryImpl @Inject constructor(
    private val dao: SearchHistoryDao
) : SearchHistoryRepository {

    override fun getRecentSearches(limit: Int): Flow<List<String>> {
        return dao.getRecent(limit).map { entities ->
            entities.map { it.keyword }
        }
    }

    override suspend fun addSearch(keyword: String) {
        dao.insert(SearchHistoryEntity(keyword = keyword))
    }

    override suspend fun removeSearch(keyword: String) {
        dao.delete(keyword)
    }

    override suspend fun clearSearches() {
        dao.deleteAll()
    }
}
