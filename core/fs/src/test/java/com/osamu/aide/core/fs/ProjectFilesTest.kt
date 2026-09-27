package com.osamu.aide.core.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `directories sort before files and both sort case-insensitively`() {
        val root = temp.newFolder("project")
        listOf("Zebra.kt", "alpha.kt").forEach { java.io.File(root, it).writeText("") }
        listOf("src", "Assets").forEach { java.io.File(root, it).mkdirs() }

        val names = ProjectFiles
            .childrenOf(FileNode(root, isDirectory = true, depth = 0))
            .map { it.name }

        assertEquals(listOf("Assets", "src", "alpha.kt", "Zebra.kt"), names)
    }

    @Test
    fun `generated and vcs directories are hidden from the tree`() {
        val root = temp.newFolder("project")
        listOf("build", ".git", ".gradle", "node_modules", "src").forEach {
            java.io.File(root, it).mkdirs()
        }

        val names = ProjectFiles
            .childrenOf(FileNode(root, isDirectory = true, depth = 0))
            .map { it.name }

        assertEquals(listOf("src"), names)
    }

    @Test
    fun `children of a file are empty rather than throwing`() {
        val file = temp.newFile("Main.kt")
        assertTrue(ProjectFiles.childrenOf(FileNode(file, isDirectory = false, depth = 0)).isEmpty())
    }

    @Test
    fun `depth increases by one per level`() {
        val root = temp.newFolder("project")
        java.io.File(root, "src").mkdirs()

        val child = ProjectFiles.childrenOf(FileNode(root, isDirectory = true, depth = 3)).single()

        assertEquals(4, child.depth)
    }

    @Test
    fun `a run of single-child directories is one row`() {
        val root = temp.newFolder("project")
        val leaf = java.io.File(root, "src/main/java/com/example/app")
        leaf.mkdirs()
        java.io.File(leaf, "MainActivity.java").writeText("")

        val child = ProjectFiles.childrenOf(FileNode(root, isDirectory = true, depth = 0)).single()

        // `src` holds only `main`, which holds only `java`, and so on down to
        // the directory that actually has a file in it.
        assertEquals("src/main/java/com/example/app", child.name)
        assertEquals(leaf, child.file)
        // One level, not six: the point of folding them is the indentation as
        // much as the taps.
        assertEquals(1, child.depth)
        assertEquals(listOf("MainActivity.java"), ProjectFiles.childrenOf(child).map { it.name })
    }

    @Test
    fun `folding stops where a directory holds more than one thing`() {
        val root = temp.newFolder("project")
        java.io.File(root, "src/main/java").mkdirs()
        java.io.File(root, "src/main/res").mkdirs()

        val child = ProjectFiles.childrenOf(FileNode(root, isDirectory = true, depth = 0)).single()

        assertEquals("src/main", child.name)
        assertEquals(listOf("java", "res"), ProjectFiles.childrenOf(child).map { it.name })
    }

    @Test
    fun `folding stops at a file`() {
        val root = temp.newFolder("project")
        java.io.File(root, "src").mkdirs()
        java.io.File(root, "src/only.txt").writeText("")

        val child = ProjectFiles.childrenOf(FileNode(root, isDirectory = true, depth = 0)).single()

        // One child, but it is not a directory: there is nothing to fold into.
        assertEquals("src", child.name)
    }

    @Test
    fun `a sibling the tree does not show does not stop the fold`() {
        val root = temp.newFolder("project")
        java.io.File(root, "src/main").mkdirs()
        // One hidden, one generated. Neither appears in the tree, so counting
        // either as a second child would un-fold a chain the user sees as
        // single -- which is why the filter runs before the count.
        java.io.File(root, "src/.DS_Store").writeText("")
        java.io.File(root, "src/build").mkdirs()

        val child = ProjectFiles.childrenOf(FileNode(root, isDirectory = true, depth = 0)).single()

        assertEquals("src/main", child.name)
    }

    @Test
    fun `a symlinked directory is not followed`() {
        val root = temp.newFolder("project")
        val inner = java.io.File(root, "src/main")
        inner.mkdirs()
        // Pointing back at an ancestor: following this produces an unbounded
        // name rather than a wrong one.
        java.nio.file.Files.createSymbolicLink(
            java.io.File(inner, "loop").toPath(),
            root.toPath(),
        )

        val child = ProjectFiles.childrenOf(FileNode(root, isDirectory = true, depth = 0)).single()

        assertEquals("src/main", child.name)
    }
}
