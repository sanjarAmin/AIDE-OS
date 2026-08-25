package com.osamu.aide.editor

import android.graphics.PorterDuff
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

class SemanticCompletionAdapter : EditorCompletionAdapter() {

    override fun getItemHeight(): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            48f,
            getContext().resources.displayMetrics
        ).toInt()
    }

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup,
        isCurrentCursorPosition: Boolean
    ): View {
        // We use the full package name for R because it might not be imported correctly yet
        val view = convertView ?: LayoutInflater.from(getContext())
            .inflate(com.osamu.aide.editor.R.layout.completion_item_semantic, parent, false)

        val item = getItem(position)
        val semanticItem = item as? SemanticCompletionItem

        val iconView = view.findViewById<ImageView>(R.id.completion_icon)
        val labelView = view.findViewById<TextView>(R.id.completion_label)
        val signatureView = view.findViewById<TextView>(R.id.completion_signature)
        val returnTypeView = view.findViewById<TextView>(R.id.completion_return_type)

        labelView.text = item.label
        labelView.setTextColor(getThemeColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY))

        signatureView.text = semanticItem?.signature ?: ""
        signatureView.visibility = if (semanticItem?.signature != null) View.VISIBLE else View.GONE
        signatureView.setTextColor(getThemeColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY))

        returnTypeView.text = semanticItem?.returnType ?: ""
        returnTypeView.visibility = if (semanticItem?.returnType != null) View.VISIBLE else View.GONE
        returnTypeView.setTextColor(getThemeColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY))

        iconView.setImageDrawable(item.icon)
        val kindColor = item.kind?.let { getKindColor(it) } ?: 0
        if (kindColor != 0) {
            iconView.setColorFilter(kindColor, PorterDuff.Mode.SRC_IN)
        } else {
            iconView.clearColorFilter()
        }

        if (isCurrentCursorPosition) {
            view.setBackgroundColor(getThemeColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT))
        } else {
            view.setBackgroundColor(0)
        }

        return view
    }

    private fun getKindColor(kind: CompletionItemKind): Int {
        val resId = when (kind) {
            CompletionItemKind.Method -> R.color.completion_kind_method
            CompletionItemKind.Field -> R.color.completion_kind_field
            CompletionItemKind.Variable -> R.color.completion_kind_variable
            CompletionItemKind.Class -> R.color.completion_kind_class
            CompletionItemKind.Interface -> R.color.completion_kind_interface
            CompletionItemKind.Enum -> R.color.completion_kind_enum
            CompletionItemKind.Keyword -> R.color.completion_kind_keyword
            CompletionItemKind.Snippet -> R.color.completion_kind_snippet
            else -> null
        }
        return if (resId != null) getContext().getColor(resId) else 0
    }
}
