package com.stevenyang.datadogproactive.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO

/**
 * Lenient smoke test for Wedge 1 gutter-icon plumbing.
 *
 * We intentionally test only the "no crash / file opens / highlighting
 * completes" contract. The fixture's `methods_of_interest` entries
 * (PaymentService.charge etc.) were authored against a hypothetical
 * swagstore tree and may not match the real paths inside
 * DataDog/tsre-microservices yet — remapping is tracked in a parallel
 * track.
 *
 * TODO: once methods_of_interest is remapped to real tsre classes, tighten
 * this test to assert a gutter icon is actually present on the opened file.
 */
class GutterIconSmokeTest {

    private val robot: RemoteRobot =
        RemoteRobot(System.getProperty("robot.server.url") ?: "http://127.0.0.1:8082")

    private val demoRepoPath: String =
        System.getProperty("demo.repo.path") ?: "demo-repos/tsre-microservices"

    private val screenshotsDir: File =
        File(System.getProperty("uiSmokeTest.screenshots.dir") ?: "build/uiSmokeTest-screenshots")
            .also { it.mkdirs() }

    @Before
    fun waitForIdeStartup() {
        waitFor(duration = Duration.ofSeconds(60), interval = Duration.ofSeconds(2)) {
            runCatching {
                robot.find(ComponentFixture::class.java, byXpath("//div[@class='IdeFrameImpl']"))
            }.isSuccess
        }
    }

    @Test
    fun opensFirstJavaFileWithoutCrashing() {
        val firstJava = findFirstJavaFile(File(demoRepoPath, "src"))
            ?: findFirstJavaFile(File(demoRepoPath))
            ?: run {
                // Skip gracefully rather than failing: the demo repo may not
                // have any .java files if clone layout changes upstream.
                System.err.println(
                    "[uiSmokeTest] No .java files under $demoRepoPath; skipping gutter smoke test."
                )
                return
            }

        val javaPath = firstJava.absolutePath.replace("\\", "\\\\").replace("'", "\\'")

        try {
            // Open the file programmatically via the robot JS bridge. This is
            // more reliable across platforms than synthesizing a Recent Files
            // popup click sequence.
            robot.runJs(
                """
                importClass(com.intellij.openapi.project.ProjectManager)
                importClass(com.intellij.openapi.vfs.LocalFileSystem)
                importClass(com.intellij.openapi.fileEditor.FileEditorManager)

                var projects = ProjectManager.getInstance().getOpenProjects()
                if (projects.length == 0) { throw "No open projects" }
                var project = projects[0]
                var vf = LocalFileSystem.getInstance().refreshAndFindFileByPath('$javaPath')
                if (vf == null) { throw "Could not locate VirtualFile for $javaPath" }
                FileEditorManager.getInstance(project).openFile(vf, true)
                """.trimIndent()
            )

            // Give the editor + daemon code analyzer a window to run.
            waitFor(duration = Duration.ofSeconds(10), interval = Duration.ofMillis(500)) {
                robot.findAll(
                    ComponentFixture::class.java,
                    byXpath("//div[@class='EditorComponentImpl']")
                ).isNotEmpty()
            }

            // Assert we got an editor pane up. This is the "did not crash" check.
            val editors = robot.findAll(
                ComponentFixture::class.java,
                byXpath("//div[@class='EditorComponentImpl']")
            )
            assertTrue(
                "Opening ${firstJava.name} did not surface any editor component",
                editors.isNotEmpty()
            )

            // TODO: after methods_of_interest is remapped to real tsre classes,
            // tighten this to:
            //   val markers = robot.findAll(
            //     ComponentFixture::class.java,
            //     byXpath("//div[@class='EditorGutterComponentImpl']//div[@accessiblename='Datadog warning']")
            //   )
            //   assertTrue("Expected at least one Datadog gutter icon", markers.isNotEmpty())
        } catch (t: Throwable) {
            runCatching {
                val img = robot.getScreenshot()
                val out = File(screenshotsDir, "gutter-smoke-fail_${System.currentTimeMillis()}.png")
                ImageIO.write(img, "png", out)
                System.err.println("[uiSmokeTest] Screenshot written to ${out.absolutePath}")
            }
            throw t
        }
    }

    private fun findFirstJavaFile(root: File): File? {
        if (!root.exists()) return null
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .firstOrNull()
    }
}
