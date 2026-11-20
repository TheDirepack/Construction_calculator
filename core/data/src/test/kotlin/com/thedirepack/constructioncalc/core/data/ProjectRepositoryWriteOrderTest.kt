package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.Project
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * File writes run as background jobs, and the threads running them can reach the write lock in any
 * order. [ShufflingDispatcher] makes that worst case happen every time: whatever order the jobs run
 * in, the store must end up matching the in-memory state.
 */
class ProjectRepositoryWriteOrderTest {

    /** Queues every task and, on [drain], runs them in a random order (deterministic by seed). */
    private class ShufflingDispatcher(seed: Int) : CoroutineDispatcher() {
        private val random = Random(seed)
        private val queue = mutableListOf<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue += block
        }
        fun drain() {
            while (queue.isNotEmpty()) queue.removeAt(random.nextInt(queue.size)).run()
        }
    }

    private class MemoryStore(initial: List<Project> = emptyList()) : ProjectStore {
        val saved = linkedMapOf<String, Project>().apply { initial.forEach { put(it.id, it) } }
        override fun loadAll() = saved.values.toList()
        override fun save(project: Project) { saved[project.id] = project }
        override fun delete(projectId: String) { saved.remove(projectId) }
    }

    private var now = 1_000L

    private fun repo(store: ProjectStore, io: ShufflingDispatcher) =
        ProjectRepository(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { now++ }, io).also { io.drain() }

    @Test fun manyQuickEditsLeaveTheLatestVersionOnDisk() {
        repeat(20) { seed ->
            val io = ShufflingDispatcher(seed)
            val store = MemoryStore()
            val repo = repo(store, io)
            val project = repo.create("Job")
            repeat(30) { i -> repo.edit(project.id) { p, t -> p.renamed("Name $i", t) } }
            io.drain()
            assertEquals("seed $seed", "Name 29", store.saved.getValue(project.id).name)
            assertEquals(repo.get(project.id), store.saved[project.id])
        }
    }

    @Test fun replaceAllRestoreKeepsRestoredProjectsThatShareIds() {
        repeat(20) { seed ->
            val io = ShufflingDispatcher(seed)
            val mine = (1..10).map { Project.create("P$it", now = it.toLong()) }
            val store = MemoryStore(mine)
            val repo = repo(store, io)
            val backup = mine.map { it.renamed(it.name + " (backup)", 5_000) }
            repo.deleteAll()
            repo.putAll(backup)
            io.drain()
            assertEquals("seed $seed", 10, store.saved.size)
            assertTrue(store.saved.values.all { it.name.endsWith("(backup)") })
        }
    }

    @Test fun deleteWinsWhenItIsTheLastChange() {
        repeat(20) { seed ->
            val io = ShufflingDispatcher(seed)
            val store = MemoryStore()
            val repo = repo(store, io)
            val project = repo.create("Job")
            repeat(10) { i -> repo.edit(project.id) { p, t -> p.renamed("N$i", t) } }
            repo.delete(project.id)
            io.drain()
            assertFalse("seed $seed", store.saved.containsKey(project.id))
        }
    }
}
