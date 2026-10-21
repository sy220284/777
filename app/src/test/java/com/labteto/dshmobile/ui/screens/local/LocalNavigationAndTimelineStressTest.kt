package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionSummary
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cold, long and out-of-order histories; sidebar and feature routes share these entry decisions. */
class LocalNavigationAndTimelineStressTest {
    private fun legacyOrder(input: List<LocalHarnessMessage>): List<String> {
        val ordered = input.toMutableList()
        val completed = ordered.filter { it.toolDeclaredAt != null }
            .sortedWith(compareBy<LocalHarnessMessage> { it.toolDeclaredAt ?: Long.MAX_VALUE }
                .thenBy { it.toolDeclaredSequence ?: Long.MAX_VALUE })
        ordered.removeAll { it.toolDeclaredAt != null }
        completed.forEach { tool ->
            val time = tool.toolDeclaredAt ?: Long.MAX_VALUE
            val index = ordered.indexOfFirst {
                (it.toolDeclaredAt ?: it.createdAt) > time
            }
            ordered.add(if (index < 0) ordered.size else index, tool)
        }
        return ordered.map { it.id }
    }

    private fun tool(i: Int, created: Long, declared: Long?): LocalHarnessMessage =
        LocalHarnessMessage(
            id = "tool-$i", role = "tool", content = "tool-result-$i",
            createdAt = created, toolDeclaredAt = declared, toolDeclaredSequence = (i % 9).toLong(),
        )

    @Test fun optimizedInsertionAgreesWithLegacyAcrossUnsortedTiesAndExtremeTimestamps() {
        val random = Random(20261021)
        repeat(250) { iteration ->
            val inputs = List(80) { i ->
                val time = when (i % 19) {
                    0 -> Long.MIN_VALUE
                    1 -> Long.MAX_VALUE
                    else -> random.nextLong(-10L, 25L)
                }
                tool(i + iteration * 80, random.nextLong(-10L, 25L),
                    if (random.nextBoolean()) time else null)
            }
            val actual = buildLocalTranscript(inputs).map { (it as LocalTranscriptItem.WorkStep).message.id }
            assertEquals("seed case $iteration", legacyOrder(inputs), actual)
        }
    }

    @Test fun twentyThousandToolRowsRetainEveryIdentityWithoutQuadraticReinsertion() {
        val random = Random(67)
        val input = List(20_000) { i ->
            tool(i, i.toLong(), if (i % 3 != 0) random.nextLong(0L, 25_000L) else null)
        }
        val items = buildLocalTranscript(input)
        assertEquals(20_000, items.size)
        val keys = items.map { it.key }
        assertEquals(20_000, keys.toSet().size)
        assertEquals(input.map { "work-step:${it.id}" }.toSet(), keys.toSet())
    }

    @Test fun rapidDrawerNavigationRetainsCorrectBackOriginAndRejectsMissingHistory() {
        val all = List(1_500) { n ->
            LocalSessionSummary(id = "session-$n", title = "对话 $n",
                updatedAt = n.toLong(), usageMode = if (n % 2 == 0) LocalUsageMode.WORK else LocalUsageMode.CHAT)
        }
        var accepted = ""
        repeat(10_000) { n ->
            val id = "session-${n % all.size}"
            val ok = acceptLocalSessionNavigation("current", id, all) {
                accepted = it
                true
            }
            assertTrue(ok)
            assertEquals(id, accepted)
            val drawer = localFeatureOpenFromDrawer(
                stack = localFeatureHome(),
                originStack = null,
                page = if (n % 2 == 0) LocalFeaturePage.SETTINGS else LocalFeaturePage.TASKS,
            )
            val back = localFeatureBack(drawer.stack, drawer.originStack)
            assertTrue(back.reopenDrawer)
            assertEquals(localFeatureHome(), back.stack)
            assertEquals(null, back.drawerOriginStack)
        }
        val before = accepted
        assertFalse(acceptLocalSessionNavigation("current", "deleted", all) {
            accepted = it
            true
        })
        assertEquals(before, accepted)
    }

    @Test fun rapidModeTabInterleavingsDoNotChangeFeatureNavigationOwnership() {
        val stack = localFeatureHome() + LocalFeaturePage.SETTINGS.name
        repeat(10_000) { n ->
            val mode = if (n % 2 == 0) LocalUsageMode.CHAT else LocalUsageMode.WORK
            assertTrue(localModeTabPreservesNavigation(mode, LocalUsageMode.WORK.name, mode))
            assertFalse(localModeTabPreservesNavigation(null, LocalUsageMode.WORK.name, mode))
            assertEquals(LocalFeaturePage.SETTINGS, localFeatureCurrent(stack))
            assertEquals(localFeatureHome(), localFeaturePop(stack))
        }
    }
}
