

package androidx.compose.ui.text


abstract class LinkAnnotation private constructor() : AnnotatedString.Annotation {
    
    abstract val linkInteractionListener: LinkInteractionListener?
    
    abstract val styles: TextLinkStyles?

    
    class Url(
        val url: String,
        override val styles: TextLinkStyles? = null,
        override val linkInteractionListener: LinkInteractionListener? = null,
    ) : LinkAnnotation() {

        
        @Suppress("ExecutorRegistration")
        fun copy(
            url: String = this.url,
            styles: TextLinkStyles? = this.styles,
            linkInteractionListener: LinkInteractionListener? = this.linkInteractionListener,
        ) = Url(url, styles, linkInteractionListener)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Url) return false

            if (url != other.url) return false
            if (styles != other.styles) return false
            if (linkInteractionListener != other.linkInteractionListener) return false

            return true
        }

        override fun hashCode(): Int {
            var result = url.hashCode()
            result = 31 * result + (styles?.hashCode() ?: 0)
            result = 31 * result + (linkInteractionListener?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String {
            return "LinkAnnotation.Url(url=$url)"
        }
    }

    
    class Clickable(
        val tag: String,
        override val styles: TextLinkStyles? = null,
        override val linkInteractionListener: LinkInteractionListener?,
    ) : LinkAnnotation() {

        
        @Suppress("ExecutorRegistration")
        fun copy(
            tag: String = this.tag,
            styles: TextLinkStyles? = this.styles,
            linkInteractionListener: LinkInteractionListener? = this.linkInteractionListener,
        ) = Clickable(tag, styles, linkInteractionListener)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Clickable) return false

            if (tag != other.tag) return false
            if (styles != other.styles) return false
            if (linkInteractionListener != other.linkInteractionListener) return false

            return true
        }

        override fun hashCode(): Int {
            var result = tag.hashCode()
            result = 31 * result + (styles?.hashCode() ?: 0)
            result = 31 * result + (linkInteractionListener?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String {
            return "LinkAnnotation.Clickable(tag=$tag)"
        }
    }
}
