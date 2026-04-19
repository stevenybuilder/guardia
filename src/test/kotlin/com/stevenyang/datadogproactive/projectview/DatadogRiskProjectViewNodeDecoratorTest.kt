package com.stevenyang.datadogproactive.projectview

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color

/**
 * Verifies the project-view decorator paints HIGH-risk fixture files (real tsre entries)
 * in orange, rolls folder counts up with an "at-risk" location-string badge, and leaves
 * unrelated files untouched.
 *
 * Uses `myFixture.addFileToProject(...)` to create VirtualFiles whose paths end in the
 * real tsre fixture suffixes (e.g. `.../paymentservicejava/PaymentServiceImpl.java`).
 * The decorator's suffix-based lookup hits on the last-3 segments, so the test does NOT
 * require the real tsre clone to be present.
 */
class DatadogRiskProjectViewNodeDecoratorTest : BasePlatformTestCase() {

    fun testHighRiskFileGetsOrangeForeground() {
        // Fixture entry: .../paymentservicejava/PaymentServiceImpl.java (HIGH)
        val vf = myFixture.addFileToProject(
            "src/paymentservice/src/main/java/com/hipstershop/paymentservicejava/PaymentServiceImpl.java",
            "package com.hipstershop.paymentservicejava; class PaymentServiceImpl {}"
        ).virtualFile

        val (data, _) = decorate(vf)

        assertNotNull("HIGH-risk file should receive a forced foreground color", data.forcedTextForeground)
        // Orange foreground in light mode: 0xE65100. JBColor preserves the "light" RGB.
        val actual = data.forcedTextForeground!!
        assertTrue(
            "Expected an orange-ish foreground (R>200, G<150, B<80); got $actual",
            actual.red > 200 && actual.green < 180 && actual.blue < 90
        )
        assertNotNull("Expected a location string on at-risk file", data.locationString)
        assertTrue(
            "Location string should mention 'at risk'; got '${data.locationString}'",
            data.locationString!!.contains("at risk", ignoreCase = true)
        )
    }

    fun testDirectoryAboveAtRiskFileGetsLocationBadge() {
        // Create the file so the index sees it, then decorate the parent directory.
        val file = myFixture.addFileToProject(
            "src/paymentservice/src/main/java/com/hipstershop/paymentservicejava/PaymentServiceImpl.java",
            "package com.hipstershop.paymentservicejava; class PaymentServiceImpl {}"
        ).virtualFile

        val parentDir = file.parent!!  // .../paymentservicejava
        val (data, _) = decorate(parentDir)

        assertNull(
            "Directory nodes should NOT receive orange foreground — too noisy",
            data.forcedTextForeground
        )
        val location = data.locationString
        assertNotNull("Expected an 'at-risk files' badge on the parent directory", location)
        assertTrue(
            "Badge should mention at-risk count; got '$location'",
            location!!.contains("at-risk")
        )
    }

    fun testUnrelatedFileGetsNoDecoration() {
        val vf = myFixture.addFileToProject(
            "build.gradle.kts",
            "// nothing to see here"
        ).virtualFile

        val (data, _) = decorate(vf)

        assertNull(
            "Unrelated file should NOT receive a forced foreground",
            data.forcedTextForeground
        )
        assertNull(
            "Unrelated file should NOT receive an at-risk location string",
            data.locationString
        )
    }

    // ---- helpers ----

    private fun decorate(vf: VirtualFile): Pair<PresentationData, ProjectViewNode<*>> {
        val data = PresentationData()
        val node: ProjectViewNode<*> = if (vf.isDirectory) {
            val psiDir = PsiManager.getInstance(project).findDirectory(vf)!!
            PsiDirectoryNode(project, psiDir, ViewSettings.DEFAULT)
        } else {
            val psiFile = PsiManager.getInstance(project).findFile(vf)!!
            PsiFileNode(project, psiFile, ViewSettings.DEFAULT)
        }
        // Seed presentableText so the decorator has something to re-attribute.
        data.presentableText = vf.name
        DatadogRiskProjectViewNodeDecorator().decorate(node, data)
        return data to node
    }

    @Suppress("unused")
    private fun Color.isOrangeIsh(): Boolean = red > 200 && green < 180 && blue < 90
}
