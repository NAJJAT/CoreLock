package com.privacyguard.app.ui

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

/**
 * B-1 (bug-fix round 2): a screen that started a Room observer on the main thread waited
 * there for Room's invalidation-tracker lock, which a blocklist write could hold for many
 * seconds: Android showed "not responding". Every observer now starts off the caller's
 * thread, through one helper.
 */
class DbChangesOffMainTest {

    @Test
    fun observer_starts_on_another_thread_than_the_collector() = runBlocking {
        val mainLike = Executors.newSingleThreadExecutor { r -> Thread(r, "main-like") }
        val io = Executors.newSingleThreadExecutor { r -> Thread(r, "io-like") }
        try {
            var startedOn: Thread? = null
            val source = flow { startedOn = Thread.currentThread(); emit(setOf("rules")) }
            val collector = withContext(mainLike.asCoroutineDispatcher()) {
                source.offMain(io.asCoroutineDispatcher()).first()
                Thread.currentThread()
            }
            assertNotSame("the observer must not start on the collector's thread", collector, startedOn)
            assertEquals("io-like", startedOn?.name)
        } finally {
            mainLike.shutdownNow(); io.shutdownNow()
        }
    }

    @Test
    fun every_room_observer_goes_through_the_off_main_helper() {
        val offenders = listOf("src/main/java", "src/enterprise/java").flatMap { root ->
            File(root).walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "DbChanges.kt" }
                .filter { "invalidationTracker.createFlow(" in it.readText() }
                .map { it.path }.toList()
        }
        assertEquals("start Room observers with tableChanges(), not createFlow()", emptyList<String>(), offenders)
    }

    /**
     * A DAO method that returns a Flow starts the same Room observer when it is collected,
     * in the collector's context. Collected on the main thread (a ViewModel scope, a
     * composable) it waited behind a list refresh and froze the app: the emulator check of
     * bug-fix round 2 caught this after the first fix. Every DAO flow is moved off the main
     * thread where it is taken from the DAO.
     */
    @Test
    fun every_dao_flow_is_moved_off_the_main_thread() {
        val daoFlows = File("src/main/java/com/privacyguard/app/data/db").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> Regex("""fun (\w+)\([^)]*\)\s*:\s*Flow<""").findAll(f.readText()).map { it.groupValues[1] } }
            .toSet()
        assertTrue("found the DAO flows: $daoFlows", daoFlows.size >= 8)
        val call = Regex("""(?:dao|Dao\(\))\.(${daoFlows.joinToString("|")})\(""")
        val offenders = listOf("src/main/java", "src/enterprise/java").flatMap { root ->
            File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) -> call.containsMatchIn(line) && ".offMain()" !in line }
                    .map { (i, line) -> "${f.name}:${i + 1}: ${line.trim()}" }
            }.toList()
        }
        assertEquals("take DAO flows with .offMain()", emptyList<String>(), offenders)
    }
}
