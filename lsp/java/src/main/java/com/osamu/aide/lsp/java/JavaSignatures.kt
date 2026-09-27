package com.osamu.aide.lsp.java

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.NewClassTree
import com.sun.source.tree.Tree
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.lang.model.type.DeclaredType

/**
 * The signature of the call the cursor is sitting inside.
 *
 * Answers a different question from completion, and needs a different search to
 * do it. Completion asks "what could go here", and [FindCursor] answers it by
 * looking for the identifier or member select *under* the caret. Signature help
 * asks "what does the call I am already inside expect" -- and between two
 * parentheses there is frequently no node under the caret at all. `setTitle()`
 * with the caret between the parens has an empty argument list; the only tree
 * that spans that position is the invocation itself, several levels up.
 *
 * So this scans for containment rather than for a leaf: the innermost call
 * whose source range covers the offset. Reusing [FindCursor] here returns the
 * compilation unit and finds nothing, which is how the first version of this
 * behaved.
 */
internal object JavaSignatures {

    fun at(compilation: Compilation, offset: Int): String? {
        val call = EnclosingCall(compilation).scan(compilation.unit, offset.toLong())
            ?: return null

        val leaf = call.leaf
        val resolved = when (leaf) {
            is MethodInvocationTree -> compilation.trees.getElement(
                TreePath(call, leaf.methodSelect),
            )
            // `new Foo(` resolves to its constructor directly rather than
            // through a select.
            else -> compilation.trees.getElement(call)
        }
        if (resolved is ExecutableElement) return format(resolved)

        // Nothing resolved, which is the normal case rather than the exception:
        // a hint is wanted precisely while the argument list is still being
        // typed, and `setTitle()` matches no overload of a method that always
        // takes one. Fall back to looking the name up among the candidates.
        return (leaf as? MethodInvocationTree)?.let { overloadFor(compilation, call, it) }
    }

    /**
     * The best candidate for an invocation that does not yet type-check.
     *
     * "Best" is the one with the most parameters: with nothing typed there is
     * no way to tell overloads apart, and the fullest signature says the most
     * about what the call can take. Where others exist they are counted rather
     * than listed -- `setTitle(CharSequence) +1 more` is a hint; four stacked
     * signatures over a phone keyboard is a wall.
     */
    private fun overloadFor(
        compilation: Compilation,
        call: TreePath,
        invocation: MethodInvocationTree,
    ): String? {
        val (receiver, name) = when (val select = invocation.methodSelect) {
            is MemberSelectTree -> {
                val type = compilation.trees.getTypeMirror(TreePath(call, select.expression))
                ((type as? DeclaredType)?.asElement() as? TypeElement) to select.identifier.toString()
            }
            // A bare `foo(` is a call on the class being edited.
            is IdentifierTree ->
                compilation.trees.getScope(call)?.enclosingClass to select.name.toString()
            else -> null to null
        }
        if (receiver == null || name == null) return null

        val candidates = compilation.task.elements.getAllMembers(receiver)
            .filterIsInstance<ExecutableElement>()
            .filter { it.simpleName.contentEquals(name) }
        val best = candidates.maxByOrNull { it.parameters.size } ?: return null

        return format(best) + if (candidates.size > 1) " +${candidates.size - 1} more" else ""
    }

    /**
     * The innermost invocation whose range covers the offset.
     *
     * Depth-first, keeping the last match: a scan reaches an outer call before
     * the inner one it contains, so for `outer(inner(|))` the inner call is
     * seen second and is the one the caret is actually in.
     */
    private class EnclosingCall(private val compilation: Compilation) :
        TreePathScanner<TreePath, Long>() {

        private var root: CompilationUnitTree? = null
        private var found: TreePath? = null

        override fun visitCompilationUnit(node: CompilationUnitTree, offset: Long): TreePath? {
            root = node
            super.visitCompilationUnit(node, offset)
            return found
        }

        override fun visitMethodInvocation(node: MethodInvocationTree, offset: Long): TreePath? {
            if (spans(node, offset)) found = currentPath
            return super.visitMethodInvocation(node, offset)
        }

        override fun visitNewClass(node: NewClassTree, offset: Long): TreePath? {
            if (spans(node, offset)) found = currentPath
            return super.visitNewClass(node, offset)
        }

        private fun spans(node: Tree, offset: Long): Boolean {
            val positions = compilation.trees.sourcePositions
            val start = positions.getStartPosition(root, node)
            val end = positions.getEndPosition(root, node)
            return start >= 0 && offset in start..end
        }
    }

    /**
     * `setTitle(CharSequence title): void`, with simple type names only.
     *
     * Qualified names are correct and unreadable in a hint that has to fit
     * above a phone keyboard: `setTitle(java.lang.CharSequence)` spends its
     * width on the part the reader already knows.
     */
    private fun format(method: ExecutableElement): String {
        val name = method.simpleName.toString().let {
            if (it == "<init>") method.enclosingElement.simpleName.toString() else it
        }
        val parameters = method.parameters.joinToString(", ") { parameter ->
            val type = simpleName(parameter.asType().toString())
            // android.jar is compiled without -parameters, so every platform
            // method's parameters are named arg0, arg1... Printing those is
            // worse than printing nothing: it looks like the API's own naming
            // and tells the reader less than the bare type does.
            val named = parameter.simpleName.toString()
            if (SYNTHETIC_PARAMETER.matches(named)) type else "$type $named"
        }
        val returns = simpleName(method.returnType.toString())
        return if (returns == "void" || returns.isEmpty()) {
            "$name($parameters)"
        } else {
            "$name($parameters): $returns"
        }
    }

    /** `java.lang.CharSequence` -> `CharSequence`, leaving generics readable. */
    private fun simpleName(type: String): String = type.replace(QUALIFIER, "")

    private val QUALIFIER = Regex("""\b[a-z][\w]*(?:\.[a-z][\w]*)*\.""")

    /** What a class file compiled without `-parameters` calls its parameters. */
    private val SYNTHETIC_PARAMETER = Regex("""arg\d+""")
}
