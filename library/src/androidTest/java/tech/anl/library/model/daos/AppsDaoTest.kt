package tech.anl.library.model.daos

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.InstrumentationRegistry
import androidx.test.filters.SmallTest
import org.junit.After
import org.junit.Assert.* // ktlint-disable no-wildcard-imports
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import tech.anl.library.androidTestHelpers.blockingObserve
import tech.anl.library.model.entities.App
import tech.anl.library.model.repositories.AnlDatabase

@SmallTest
class AppsDaoTest {

    @get:Rule
    val instantExectutorRule = InstantTaskExecutorRule()

    private lateinit var db: AnlDatabase

    @Before
    fun initDb() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getContext(),
                AnlDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun insertApplicationAndGetByName() {
        val inserted = App(name = DEFAULT_NAME)

        db.appsDao().insertApp(inserted)
        val retrieved = db.appsDao().getAppByName(DEFAULT_NAME)

        assertNotNull(retrieved)
        assertEquals(inserted, retrieved)
    }

    @Test
    fun dbApplicationIsReplacedOnConflict() {
        val app1 = App(name = "test", category = "")
        val app2 = App(name = "test", category = "test")
        db.appsDao().insertApp(app1)
        db.appsDao().insertApp(app2)

        val retrieved = db.appsDao().getAllApps().blockingObserve()!!

        assertTrue(retrieved.contains(app2))
        assertFalse(retrieved.contains(app1))
    }

    @Test
    fun appsMissingFromTheListAreDeleted() {
        val kept = App(name = "kept")
        val dropped = App(name = "dropped")
        db.appsDao().insertApp(kept)
        db.appsDao().insertApp(dropped)

        db.appsDao().deleteAppsNotIn(listOf("kept", "new"))

        val retrieved = db.appsDao().getAllApps().blockingObserve()!!
        assertEquals(listOf(kept), retrieved)
    }

    companion object {
        val DEFAULT_NAME = "test"
    }
}