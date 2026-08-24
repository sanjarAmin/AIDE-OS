package com.osamu.aide.lsp.java

import com.sun.source.tree.MemberSelectTree
import com.sun.source.util.TreePath
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeMirror

/**
 * Turns a cursor position in a compiled file into proposals.
 *
 * Two shapes, which is most of what an editor asks for:
 *
 * - **After a dot** -- the members of whatever is to the left of it, filtered
 *   by what is actually accessible from here. Static access (`Build.VERSION.`)
 *   and instance access (`activity.`) look identical in the tree and are told
 *   apart by whether the expression resolved to a type or to a value.
 * - **Anywhere else** -- what is in scope: locals, parameters, fields, and the
 *   members of the enclosing class.
 */
internal object JavaCompletions {

    fun at(compilation: Compilation, path: TreePath, prefix: String): List<CompletionItem> {
        val leaf = path.leaf
        val items = when {
            leaf is MemberSelectTree -> membersOf(compilation, path, leaf)
            else -> inScope(compilation, path)
        }

        return items
            .filter { it.label.startsWith(prefix, ignoreCase = true) }
            // Case-insensitive so `sys` finds `getSystemService` once a member
            // filter is applied upstream, but ordered case-sensitively first so
            // an exact-case match is not buried under one that merely matches.
            .distinctBy { it.label to it.kind }
            .sortedWith(compareBy({ !it.label.startsWith(prefix) }, { it.label }))
    }

    private fun membersOf(
        compilation: Compilation,
        path: TreePath,
        leaf: MemberSelectTree,
    ): List<CompletionItem> {
        val expression = TreePath(path, leaf.expression)
        val element = compilation.trees.getElement(expression)
        val type: TypeMirror? = compilation.trees.getTypeMirror(expression)

        // `Build.VERSION.` names a type, `activity.` names a value. Only the
        // first should offer statics, and only the second should offer the rest.
        val staticAccess = element is TypeElement
        val declared = when {
            staticAccess -> compilation.task.types.getDeclaredType(element as TypeElement)
            type is DeclaredType -> type
            else -> return emptyList()
        }
        val typeElement = declared.asElement() as? TypeElement ?: return emptyList()
        val scope = compilation.trees.getScope(path)

        return compilation.task.elements.getAllMembers(typeElement)
            .asSequence()
            .filter { member -> member.isStatic() == staticAccess || !staticAccess }
            .filter { member ->
                runCatching { compilation.trees.isAccessible(scope, member, declared) }
                    .getOrDefault(false)
            }
            .filter { it.kind != ElementKind.CONSTRUCTOR && it.kind != ElementKind.STATIC_INIT }
            .map { it.toItem() }
            .toList()
    }

    private fun inScope(compilation: Compilation, path: TreePath): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()
        var scope = runCatching { compilation.trees.getScope(path) }.getOrNull()

        // Walk outwards: the innermost block first, then its method, then the
        // class. Nearer declarations shadow further ones, and distinctBy in
        // `at` keeps the first of each name, so order here is the shadowing.
        while (scope != null) {
            scope.localElements.forEach { items += it.toItem() }
            scope.enclosingClass?.let { enclosing ->
                compilation.task.elements.getAllMembers(enclosing).forEach { items += it.toItem() }
            }
            scope = scope.enclosingScope
        }

        JAVA_KEYWORDS.forEach { items += CompletionItem(it, CompletionKind.KEYWORD) }
        return items
    }

    private fun Element.isStatic(): Boolean =
        javax.lang.model.element.Modifier.STATIC in modifiers

    private fun Element.toItem(): CompletionItem {
        val name = simpleName.toString()
        return when (this) {
            is ExecutableElement -> CompletionItem(
                label = name,
                kind = CompletionKind.METHOD,
                detail = parameters.joinToString(
                    prefix = "(",
                    postfix = "): $returnType",
                ) { "${it.asType()} ${it.simpleName}" },
            )

            is TypeElement -> CompletionItem(name, CompletionKind.CLASS, detail = qualifiedName.toString())

            else -> when (kind) {
                ElementKind.FIELD, ElementKind.ENUM_CONSTANT ->
                    CompletionItem(name, CompletionKind.FIELD, detail = asType().toString())

                ElementKind.PACKAGE -> CompletionItem(name, CompletionKind.PACKAGE)

                else -> CompletionItem(name, CompletionKind.VARIABLE, detail = asType().toString())
            }
        }
    }

    /**
     * Offered everywhere rather than by grammar position.
     *
     * Deciding where `else` is legal needs the parser's state, which is not
     * available once the tree is built. A keyword that cannot go here is a
     * wrong proposal near the bottom of a filtered list; a missing `return` is
     * a feature that looks broken.
     */
    private val JAVA_KEYWORDS = listOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new",
        "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "true", "false", "null",
    )
}
