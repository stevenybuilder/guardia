package com.stevenyang.datadogproactive.toolwindow

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Container
import javax.swing.JPanel

/**
 * Smoke test for the Wedge 1 proactive inbox. Confirms [MethodsAtRiskInbox.refresh]
 * produces one row per method-of-interest after the off-EDT fetch resolves.
 */
class MethodsAtRiskInboxTest : BasePlatformTestCase() {

    fun testRefreshProducesOneRowPerMethodOfInterest() {
        val inbox = MethodsAtRiskInbox(project)
        val expected = inbox.snapshot().size
        assertTrue("Fixture should expose methods-of-interest", expected > 0)

        inbox.refresh()
        // Drain the pooled thread + EDT. Retry a few times so the refresh's
        // executeOnPooledThread → invokeLater chain has time to resolve.
        for (i in 0 until 40) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (countRowPanels(inbox) >= expected) break
            Thread.sleep(50)
        }

        val rowCount = countRowPanels(inbox)
        assertEquals(
            "Inbox should render one row per method-of-interest",
            expected,
            rowCount,
        )
    }

    /** Count the nested Row panels that correspond 1:1 with MethodContext entries. */
    private fun countRowPanels(root: Container): Int {
        var count = 0
        val stack = ArrayDeque<Container>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            // MethodsAtRiskInbox's Row class is private; detect it by the BorderLayout
            // row shape (BorderLayout with >= 2 children including a chip + center).
            if (c is JPanel && c.layout is java.awt.BorderLayout && c.componentCount >= 2) {
                // Ensure it's not the outer containers — rows carry a maximum height of 56.
                if (c.maximumSize != null && c.maximumSize.height == 56) count++
            }
            for (child in c.components) if (child is Container) stack.add(child)
        }
        return count
    }
}
