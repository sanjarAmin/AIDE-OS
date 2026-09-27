;; Written for the XML grammar in com.itsaky.androidide.treesitter, which is a
;; different grammar from the one tree-sitter-grammars/tree-sitter-xml publishes
;; queries for -- it models an element as (element (name) (attribute ...)) with
;; no (PI) or (EncName) anywhere. The upstream query does not compile against it
;; at all, so this one is ours. See README.md.
;;
;; Patterns are deliberately flat: capturing a node type that exists is safe,
;; while asserting a parent/child shape this grammar does not use fails the
;; whole query and takes every other rule down with it.

(comment) @comment

;; Element and attribute names both come through as (name).
(name) @tag

(attr_value) @string
(eq) @operator

(cdata) @string
(cdata_start) @punctuation.bracket
(cdata_end) @punctuation.bracket

(entity_ref) @constant
(char_ref) @constant

(xml_version_value) @string
(xml_encoding_value) @string

["<" ">" "/" "<?" "?>"] @punctuation.bracket

;; The XML declaration's own vocabulary, which the grammar tokenises literally.
["xml" "version" "encoding" "xmlns"] @keyword
