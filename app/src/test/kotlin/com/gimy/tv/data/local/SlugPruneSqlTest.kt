package com.gimy.tv.data.local

import com.gimy.tv.data.local.dao.EMBED_SLUG_PRUNE_SQL
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * 啟動清理不能刪掉「收藏或觀看紀錄還在用」的 slug 對照。
 *
 * Jable／XNXX／Imaple 的 vodId 是 slug 的單向雜湊，收藏只存 id；對照只在列表頁寫入，開收藏
 * 不會更新時間戳。清理若只看 cachedAt，30 天沒在列表出現的收藏就永遠打不開（2026-10-03 查證）。
 * 這裡直接執行與 DAO @Query 相同的常數字串，測的就是正式那一句。
 */
class SlugPruneSqlTest {
    private lateinit var db: Connection
    private val threshold = 1_000_000L
    private val old = threshold - 1
    private val fresh = threshold + 1

    @Before fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("CREATE TABLE favorites (vodId INTEGER NOT NULL, sourceType TEXT NOT NULL)")
        exec("CREATE TABLE watch_history (vodId INTEGER NOT NULL, sourceType TEXT NOT NULL)")
        exec(
            "CREATE TABLE embed_slugs (vodId INTEGER NOT NULL, slug TEXT NOT NULL, " +
                "sourceType TEXT NOT NULL, cachedAt INTEGER NOT NULL, PRIMARY KEY(sourceType, vodId))"
        )
    }

    @After fun tearDown() = db.close()

    private fun exec(sql: String) {
        db.createStatement().use { it.execute(sql) }
    }

    private fun slug(vodId: Long, source: String, cachedAt: Long) =
        exec("INSERT INTO embed_slugs VALUES ($vodId, 's$vodId', '$source', $cachedAt)")

    private fun prune(sql: String): Int = db.prepareStatement(sql).use { st ->
        st.setLong(1, threshold)
        st.executeUpdate()
    }

    private fun remaining(table: String): Set<Long> = db.createStatement().use { st ->
        st.executeQuery("SELECT vodId FROM $table").use { rs ->
            buildSet { while (rs.next()) add(rs.getLong(1)) }
        }
    }

    @Test fun `old rows nobody references are pruned`() {
        slug(1, "JABLE_TV", old)
        prune(EMBED_SLUG_PRUNE_SQL)
        assertThat(remaining("embed_slugs")).isEmpty()
    }

    @Test fun `favorited rows survive however old`() {
        slug(2, "JABLE_TV", old)
        exec("INSERT INTO favorites VALUES (2, 'JABLE_TV')")
        prune(EMBED_SLUG_PRUNE_SQL)
        assertThat(remaining("embed_slugs")).containsExactly(2L)
    }

    @Test fun `rows in watch history survive however old`() {
        slug(3, "IMAPLE_TV", old)
        exec("INSERT INTO watch_history VALUES (3, 'IMAPLE_TV')")
        prune(EMBED_SLUG_PRUNE_SQL)
        assertThat(remaining("embed_slugs")).containsExactly(3L)
    }

    @Test fun `fresh rows survive`() {
        slug(4, "XNXX", fresh)
        prune(EMBED_SLUG_PRUNE_SQL)
        assertThat(remaining("embed_slugs")).containsExactly(4L)
    }

    /** 各來源的 id 空間各自獨立：別的來源收藏了同一個數字，不能替這一列擋下清理。 */
    @Test fun `a favorite on another source does not protect this row`() {
        slug(5, "JABLE_TV", old)
        exec("INSERT INTO favorites VALUES (5, 'XNXX')")
        prune(EMBED_SLUG_PRUNE_SQL)
        assertThat(remaining("embed_slugs")).isEmpty()
    }
}
