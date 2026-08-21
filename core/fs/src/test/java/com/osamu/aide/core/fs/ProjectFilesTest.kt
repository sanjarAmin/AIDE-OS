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
}
