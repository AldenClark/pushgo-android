

package androidx.compose.ui.text

import androidx.collection.mutableIntListOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.text.AnnotatedString.Annotation
import androidx.compose.ui.text.AnnotatedString.Builder
import androidx.compose.ui.text.AnnotatedString.Range
import androidx.compose.ui.text.internal.checkPrecondition
import androidx.compose.ui.text.internal.requirePrecondition
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType.Companion.Em
import androidx.compose.ui.unit.TextUnitType.Companion.Sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastFilteredMap
import androidx.compose.ui.util.fastFlatMap
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import kotlin.jvm.JvmName


@Immutable
class AnnotatedString
internal constructor(internal val annotations: List<Range<out Annotation>>?, val text: String) :
    CharSequence {

    internal val spanStylesOrNull: List<Range<SpanStyle>>?
    
    val spanStyles: List<Range<SpanStyle>>
        get() = spanStylesOrNull ?: listOf()

    internal val paragraphStylesOrNull: List<Range<ParagraphStyle>>?
    
    val paragraphStyles: List<Range<ParagraphStyle>>
        get() = paragraphStylesOrNull ?: listOf()

    
    constructor(
        text: String,
        spanStyles: List<Range<SpanStyle>> = listOf(),
        paragraphStyles: List<Range<ParagraphStyle>> = listOf(),
    ) : this(constructAnnotationsFromSpansAndParagraphs(spanStyles, paragraphStyles), text)

    
    constructor(
        text: String,
        annotations: List<Range<out Annotation>> = listOf(),
    ) : this(annotations.ifEmpty { null }, text)

    init {
        var spanStyles: MutableList<Range<SpanStyle>>? = null
        var paragraphStyles: MutableList<Range<ParagraphStyle>>? = null
        @Suppress("UNCHECKED_CAST")
        annotations?.fastForEach { annotation ->
            if (annotation.item is SpanStyle) {
                if (spanStyles == null) {
                    spanStyles = mutableListOf()
                }
                spanStyles!!.add(annotation as Range<SpanStyle>)
            } else if (annotation.item is ParagraphStyle) {
                if (paragraphStyles == null) {
                    paragraphStyles = mutableListOf()
                }
                paragraphStyles!!.add(annotation as Range<ParagraphStyle>)
            }
        }
        spanStylesOrNull = spanStyles
        paragraphStylesOrNull = paragraphStyles

        @Suppress("ListIterator") val sorted = paragraphStylesOrNull?.sortedBy { it.start }
        if (!sorted.isNullOrEmpty()) {
            val previousEnds = mutableIntListOf(sorted.first().end)
            for (i in 1 until sorted.size) {
                val current = sorted[i]
                while (previousEnds.isNotEmpty()) {
                    val previousEnd = previousEnds.last()
                    if (current.start >= previousEnd) {
                        previousEnds.removeAt(previousEnds.lastIndex)
                    } else {
                        requirePrecondition(current.end <= previousEnd) {
                            "Paragraph overlap not allowed, end ${current.end} should be less than or equal to $previousEnd"
                        }
                        break
                    }
                }
                previousEnds.add(current.end)
            }
        }
    }

    override val length: Int
        get() = text.length

    override operator fun get(index: Int): Char = text[index]

    
    override fun subSequence(startIndex: Int, endIndex: Int): AnnotatedString {
        requirePrecondition(startIndex <= endIndex) {
            "start ($startIndex) should be less or equal to end ($endIndex)"
        }
        if (startIndex == 0 && endIndex == text.length) return this
        val text = text.substring(startIndex, endIndex)
        return AnnotatedString(
            text = text,
            annotations = filterRanges(annotations, startIndex, endIndex),
        )
    }

    
    fun subSequence(range: TextRange): AnnotatedString {
        return subSequence(range.min, range.max)
    }

    @Stable
    operator fun plus(other: AnnotatedString): AnnotatedString {
        return with(Builder(this)) {
            append(other)
            toAnnotatedString()
        }
    }

    
    @Suppress("UNCHECKED_CAST", "KotlinRedundantDiagnosticSuppress")
    fun getStringAnnotations(tag: String, start: Int, end: Int): List<Range<String>> =
        (annotations?.fastFilteredMap({
            it.item is StringAnnotation && tag == it.tag && intersect(start, end, it.start, it.end)
        }) {
            it.unbox()
        } ?: listOf())

    
    fun hasStringAnnotations(tag: String, start: Int, end: Int): Boolean =
        annotations?.fastAny {
            it.item is StringAnnotation && tag == it.tag && intersect(start, end, it.start, it.end)
        } ?: false

    
    @Suppress("UNCHECKED_CAST", "KotlinRedundantDiagnosticSuppress")
    fun getStringAnnotations(start: Int, end: Int): List<Range<String>> =
        annotations?.fastFilteredMap({
            it.item is StringAnnotation && intersect(start, end, it.start, it.end)
        }) {
            it.unbox()
        } ?: listOf()

    
    @Suppress("UNCHECKED_CAST")
    fun getTtsAnnotations(start: Int, end: Int): List<Range<TtsAnnotation>> =
        ((annotations?.fastFilter {
            it.item is TtsAnnotation && intersect(start, end, it.start, it.end)
        } ?: listOf())
            as List<Range<TtsAnnotation>>)

    
    @ExperimentalTextApi
    @Suppress("UNCHECKED_CAST", "Deprecation")
    @Deprecated("Use LinkAnnotation API instead", ReplaceWith("getLinkAnnotations(start, end)"))
    fun getUrlAnnotations(start: Int, end: Int): List<Range<UrlAnnotation>> =
        ((annotations?.fastFilter {
            it.item is UrlAnnotation && intersect(start, end, it.start, it.end)
        } ?: listOf())
            as List<Range<UrlAnnotation>>)

    
    @Suppress("UNCHECKED_CAST")
    fun getLinkAnnotations(start: Int, end: Int): List<Range<LinkAnnotation>> =
        ((annotations?.fastFilter {
            it.item is LinkAnnotation && intersect(start, end, it.start, it.end)
        } ?: listOf())
            as List<Range<LinkAnnotation>>)

    
    fun hasLinkAnnotations(start: Int, end: Int): Boolean =
        annotations?.fastAny {
            it.item is LinkAnnotation && intersect(start, end, it.start, it.end)
        } ?: false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnnotatedString) return false
        if (text != other.text) return false
        if (annotations != other.annotations) return false
        return true
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + (annotations?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return text
    }

    
    fun hasEqualAnnotations(other: AnnotatedString): Boolean = this.annotations == other.annotations

    
    fun mapAnnotations(
        transform: (Range<out Annotation>) -> Range<out Annotation>
    ): AnnotatedString {
        val builder = Builder(this)
        builder.mapAnnotations(transform)
        return builder.toAnnotatedString()
    }

    
    fun flatMapAnnotations(
        transform: (Range<out Annotation>) -> List<Range<out Annotation>>
    ): AnnotatedString {
        val builder = Builder(this)
        builder.flatMapAnnotations(transform)
        return builder.toAnnotatedString()
    }

    
    @Immutable
    @Suppress("DataClassDefinition")
    data class Range<T>(val item: T, val start: Int, val end: Int, val tag: String) {
        constructor(item: T, start: Int, end: Int) : this(item, start, end, "")

        init {
            requirePrecondition(start <= end) { "Reversed range is not supported" }
        }
    }

    
    class Builder(capacity: Int = 16) : Appendable {

        private data class MutableRange<T>(
            val item: T,
            val start: Int,
            var end: Int = Int.MIN_VALUE,
            val tag: String = "",
        ) {
            
            fun toRange(defaultEnd: Int = Int.MIN_VALUE): Range<T> {
                val end = if (end == Int.MIN_VALUE) defaultEnd else end
                checkPrecondition(end != Int.MIN_VALUE) { "Item.end should be set first" }
                return Range(item = item, start = start, end = end, tag = tag)
            }

            
            fun <R> toRange(transform: (T) -> R, defaultEnd: Int = Int.MIN_VALUE): Range<R> {
                val end = if (end == Int.MIN_VALUE) defaultEnd else end
                checkPrecondition(end != Int.MIN_VALUE) { "Item.end should be set first" }
                return Range(item = transform(item), start = start, end = end, tag = tag)
            }

            companion object {
                fun <T> fromRange(range: Range<T>) =
                    MutableRange(range.item, range.start, range.end, range.tag)
            }
        }

        private val text: StringBuilder = StringBuilder(capacity)
        private val styleStack: MutableList<MutableRange<out Any>> = mutableListOf()
        
        private val annotations = mutableListOf<MutableRange<out Annotation>>()

        
        constructor(text: String) : this() {
            append(text)
        }

        
        constructor(text: AnnotatedString) : this() {
            append(text)
        }

        
        val length: Int
            get() = text.length

        
        fun append(text: String) {
            this.text.append(text)
        }

        @Deprecated(
            message =
                "Replaced by the append(Char) method that returns an Appendable. " +
                    "This method must be kept around for binary compatibility.",
            level = DeprecationLevel.HIDDEN,
        )
        @Suppress("FunctionName", "unused")
        @JvmName("append")
        fun deprecated_append_returning_void(char: Char) {
            append(char)
        }

        
        @Suppress("BuilderSetStyle", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
        override fun append(text: CharSequence?): Builder {
            if (text is AnnotatedString) {
                append(text)
            } else {
                this.text.append(text)
            }
            return this
        }

        
        @Suppress("BuilderSetStyle", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
        override fun append(text: CharSequence?, start: Int, end: Int): Builder {
            if (text is AnnotatedString) {
                append(text, start, end)
            } else {
                this.text.append(text, start, end)
            }
            return this
        }
        @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
        override fun append(char: Char): Builder {
            this.text.append(char)
            return this
        }

        
        fun append(text: AnnotatedString) {
            val start = this.text.length
            this.text.append(text.text)
            text.annotations?.fastForEach {
                annotations.add(MutableRange(it.item, start + it.start, start + it.end, it.tag))
            }
        }

        
        @Suppress("BuilderSetStyle")
        fun append(text: AnnotatedString, start: Int, end: Int) {
            val insertionStart = this.text.length
            this.text.append(text.text, start, end)
            text.getLocalAnnotations(start, end)?.fastForEach {
                annotations.add(
                    MutableRange(
                        it.item,
                        insertionStart + it.start,
                        insertionStart + it.end,
                        it.tag,
                    )
                )
            }
        }

        
        fun addStyle(style: SpanStyle, start: Int, end: Int) {
            annotations.add(MutableRange(item = style, start = start, end = end))
        }

        
        fun addStyle(style: ParagraphStyle, start: Int, end: Int) {
            annotations.add(MutableRange(item = style, start = start, end = end))
        }

        
        fun addStringAnnotation(tag: String, annotation: String, start: Int, end: Int) {
            annotations.add(
                MutableRange(
                    item = StringAnnotation(annotation),
                    start = start,
                    end = end,
                    tag = tag,
                )
            )
        }

        
        @Suppress("SetterReturnsThis")
        fun addTtsAnnotation(ttsAnnotation: TtsAnnotation, start: Int, end: Int) {
            annotations.add(MutableRange(ttsAnnotation, start, end))
        }

        
        @ExperimentalTextApi
        @Suppress("SetterReturnsThis", "Deprecation")
        @Deprecated(
            "Use LinkAnnotation API for links instead",
            ReplaceWith("addLink(, start, end)"),
        )
        fun addUrlAnnotation(urlAnnotation: UrlAnnotation, start: Int, end: Int) {
            annotations.add(MutableRange(urlAnnotation, start, end))
        }

        
        @Suppress("SetterReturnsThis")
        fun addLink(url: LinkAnnotation.Url, start: Int, end: Int) {
            annotations.add(MutableRange(url, start, end))
        }

        
        @Suppress("SetterReturnsThis")
        fun addLink(clickable: LinkAnnotation.Clickable, start: Int, end: Int) {
            annotations.add(MutableRange(clickable, start, end))
        }

        
        fun addBullet(bullet: Bullet, start: Int, end: Int) {
            annotations.add(MutableRange(item = bullet, start = start, end = end))
        }

        
        fun addBullet(bullet: Bullet, indentation: TextUnit, start: Int, end: Int) {
            val bulletParStyle = ParagraphStyle(textIndent = TextIndent(indentation, indentation))
            annotations.add(MutableRange(item = bulletParStyle, start = start, end = end))
            annotations.add(MutableRange(item = bullet, start = start, end = end))
        }

        
        fun pushStyle(style: SpanStyle): Int {
            MutableRange(item = style, start = text.length).also {
                styleStack.add(it)
                annotations.add(it)
            }
            return styleStack.size - 1
        }

        
        fun pushStyle(style: ParagraphStyle): Int {
            MutableRange(item = style, start = text.length).also {
                styleStack.add(it)
                annotations.add(it)
            }
            return styleStack.size - 1
        }

        
        fun pushBullet(bullet: Bullet): Int {
            MutableRange(item = bullet, start = text.length).also {
                styleStack.add(it)
                annotations.add(it)
            }
            return styleStack.size - 1
        }

        
        class BulletScope internal constructor(internal val builder: Builder) {
            internal val bulletListSettingStack = mutableListOf<Pair<TextUnit, Bullet>>()
        }

        private val bulletScope = BulletScope(this)

        
        fun <R : Any> withBulletList(
            indentation: TextUnit = Bullet.DefaultIndentation,
            bullet: Bullet = Bullet.Default,
            block: BulletScope.() -> R,
        ): R {
            val adjustedIndentation =
                bulletScope.bulletListSettingStack.lastOrNull()?.first?.let {
                    checkPrecondition(it.type == indentation.type) {
                        "Indentation unit types of nested bullet lists must match. Current $it and previous is $indentation"
                    }
                    when (indentation.type) {
                        Sp -> (indentation.value + it.value).sp
                        Em -> (indentation.value + it.value).em
                        else -> indentation
                    }
                } ?: indentation

            val parIndex =
                pushStyle(
                    ParagraphStyle(
                        textIndent = TextIndent(adjustedIndentation, adjustedIndentation)
                    )
                )
            bulletScope.bulletListSettingStack.add(Pair(adjustedIndentation, bullet))
            return try {
                block(bulletScope)
            } finally {
                if (bulletScope.bulletListSettingStack.isNotEmpty()) {
                    bulletScope.bulletListSettingStack.removeAt(
                        bulletScope.bulletListSettingStack.lastIndex
                    )
                }
                pop(parIndex)
            }
        }

        
        fun <R : Any> BulletScope.withBulletListItem(
            bullet: Bullet? = null,
            block: Builder.() -> R,
        ): R {
            val lastItemInStack = bulletListSettingStack.lastOrNull()
            val itemIndentation = lastItemInStack?.first ?: Bullet.DefaultIndentation
            val itemBullet = bullet ?: (lastItemInStack?.second ?: Bullet.Default)
            val parIndex =
                builder.pushStyle(
                    ParagraphStyle(textIndent = TextIndent(itemIndentation, itemIndentation))
                )
            val bulletIndex = builder.pushBullet(itemBullet)
            return try {
                block(builder)
            } finally {
                builder.pop(bulletIndex)
                builder.pop(parIndex)
            }
        }

        
        fun pushStringAnnotation(tag: String, annotation: String): Int {
            MutableRange(item = StringAnnotation(annotation), start = text.length, tag = tag).also {
                styleStack.add(it)
                annotations.add(it)
            }
            return styleStack.size - 1
        }

        
        fun pushTtsAnnotation(ttsAnnotation: TtsAnnotation): Int {
            MutableRange(item = ttsAnnotation, start = text.length).also {
                styleStack.add(it)
                annotations.add(it)
            }
            return styleStack.size - 1
        }

        
        @ExperimentalTextApi
        @Suppress("BuilderSetStyle", "Deprecation")
        @Deprecated(
            "Use LinkAnnotation API for links instead",
            ReplaceWith("pushLink(, start, end)"),
        )
        fun pushUrlAnnotation(urlAnnotation: UrlAnnotation): Int {
            MutableRange(item = urlAnnotation, start = text.length).also {
                styleStack.add(it)
                annotations.add(it)
            }
            return styleStack.size - 1
        }

        
        @Suppress("BuilderSetStyle")
        fun pushLink(link: LinkAnnotation): Int {
            MutableRange(item = link, start = text.length).also {
                styleStack.add(it)
                annotations.add(it)
            }
            return styleStack.size - 1
        }

        
        fun pop() {
            checkPrecondition(styleStack.isNotEmpty()) { "Nothing to pop." }
            val item = styleStack.removeAt(styleStack.size - 1)
            item.end = text.length
        }

        
        fun pop(index: Int) {
            checkPrecondition(index < styleStack.size) {
                "$index should be less than ${styleStack.size}"
            }
            while ((styleStack.size - 1) >= index) {
                pop()
            }
        }

        
        fun toAnnotatedString(): AnnotatedString {
            return AnnotatedString(
                text = text.toString(),
                annotations = annotations.fastMap { it.toRange(text.length) },
            )
        }

        
        internal fun mapAnnotations(transform: (Range<out Annotation>) -> Range<out Annotation>) {
            for (i in annotations.indices) {
                val newAnnotation = transform(annotations[i].toRange())
                annotations[i] = MutableRange.fromRange(newAnnotation)
            }
        }

        
        internal fun flatMapAnnotations(
            transform: (Range<out Annotation>) -> List<Range<out Annotation>>
        ) {
            val replacedAnnotations =
                annotations.fastFlatMap { annotation ->
                    transform(annotation.toRange()).fastMap { MutableRange.fromRange(it) }
                }
            annotations.clear()
            annotations.addAll(replacedAnnotations)
        }
    }

    
    sealed interface Annotation
    @Suppress("unused") private class ExhaustiveAnnotation : Annotation

    companion object {
        
        val Saver: Saver<AnnotatedString, *> = AnnotatedStringSaver
    }
}

private fun constructAnnotationsFromSpansAndParagraphs(
    spanStyles: List<Range<SpanStyle>>,
    paragraphStyles: List<Range<ParagraphStyle>>,
): List<Range<out Annotation>>? {
    return if (spanStyles.isEmpty() && paragraphStyles.isEmpty()) {
        null
    } else if (paragraphStyles.isEmpty()) {
        spanStyles
    } else if (spanStyles.isEmpty()) {
        paragraphStyles
    } else {
        ArrayList<Range<out Annotation>>(spanStyles.size + paragraphStyles.size).also { array ->
            spanStyles.fastForEach { array.add(it) }
            paragraphStyles.fastForEach { array.add(it) }
        }
    }
}


internal fun AnnotatedString.normalizedParagraphStyles(
    defaultParagraphStyle: ParagraphStyle
): List<Range<ParagraphStyle>> {
    @Suppress("ListIterator")
    val sortedParagraphs = paragraphStylesOrNull?.sortedBy { it.start } ?: listOf()
    val result = mutableListOf<Range<ParagraphStyle>>()
    var lastAdded = 0
    val stack = ArrayDeque<Range<ParagraphStyle>>()

    sortedParagraphs.fastForEach {
        val current = it.copy(defaultParagraphStyle.merge(it.item))
        while (lastAdded < current.start && stack.isNotEmpty()) {
            val lastInStack = stack.last()
            if (current.start < lastInStack.end) {
                result.add(Range(lastInStack.item, lastAdded, current.start))
                lastAdded = current.start
            } else {
                result.add(Range(lastInStack.item, lastAdded, lastInStack.end))
                lastAdded = lastInStack.end
                while (stack.isNotEmpty() && lastAdded == stack.last().end) {
                    stack.removeLast()
                }
            }
        }

        if (lastAdded < current.start) {
            result.add(Range(defaultParagraphStyle, lastAdded, current.start))
            lastAdded = current.start
        }

        val lastInStack = stack.lastOrNull()
        if (lastInStack != null) {
            if (lastInStack.start == current.start && lastInStack.end == current.end) {
                stack.removeLast()
                stack.add(Range(lastInStack.item.merge(current.item), current.start, current.end))
            } else if (lastInStack.start == lastInStack.end) {
                result.add(Range(lastInStack.item, lastInStack.start, lastInStack.end))
                stack.removeLast()
                stack.add(Range(current.item, current.start, current.end))
            } else if (lastInStack.end < current.end) {
                throw IllegalArgumentException()
            } else {
                stack.add(Range(lastInStack.item.merge(current.item), current.start, current.end))
            }
        } else {
            stack.add(Range(current.item, current.start, current.end))
        }
    }
    while (lastAdded <= text.length && stack.isNotEmpty()) {
        val lastInStack = stack.last()
        result.add(Range(lastInStack.item, lastAdded, lastInStack.end))
        lastAdded = lastInStack.end
        while (stack.isNotEmpty() && lastAdded == stack.last().end) {
            stack.removeLast()
        }
    }
    if (lastAdded < text.length) {
        result.add(Range(defaultParagraphStyle, lastAdded, text.length))
    }
    if (result.isEmpty()) {
        result.add(Range(defaultParagraphStyle, 0, 0))
    }
    return result
}


private fun AnnotatedString.getLocalParagraphStyles(
    start: Int,
    end: Int,
): List<Range<ParagraphStyle>>? {
    if (start == end) return null
    val paragraphStyles = paragraphStylesOrNull ?: return null
    if (start == 0 && end >= this.text.length) {
        return paragraphStyles
    }
    return paragraphStyles.fastFilteredMap({ intersect(start, end, it.start, it.end) }) {
        Range(
            it.item,
            it.start.fastCoerceIn(start, end) - start,
            it.end.fastCoerceIn(start, end) - start,
        )
    }
}


private fun AnnotatedString.getLocalAnnotations(
    start: Int,
    end: Int,
    predicate: ((Annotation) -> Boolean)? = null,
): List<Range<out AnnotatedString.Annotation>>? {
    if (start == end) return null
    val annotations = annotations ?: return null
    if (start == 0 && end >= this.text.length) {
        return if (predicate == null) {
            annotations
        } else {
            annotations.fastFilter { predicate(it.item) }
        }
    }
    return annotations.fastFilteredMap({
        (predicate?.invoke(it.item) ?: true) && intersect(start, end, it.start, it.end)
    }) {
        Range(
            tag = it.tag,
            item = it.item,
            start = it.start.coerceIn(start, end) - start,
            end = it.end.coerceIn(start, end) - start,
        )
    }
}


private fun AnnotatedString.substringWithoutParagraphStyles(start: Int, end: Int): AnnotatedString {
    return AnnotatedString(
        text = if (start != end) text.substring(start, end) else "",
        annotations = getLocalAnnotations(start, end) { it !is ParagraphStyle } ?: listOf(),
    )
}

internal inline fun <T> AnnotatedString.mapEachParagraphStyle(
    defaultParagraphStyle: ParagraphStyle,
    crossinline block:
        (annotatedString: AnnotatedString, paragraphStyle: Range<ParagraphStyle>) -> T,
): List<T> {
    return normalizedParagraphStyles(defaultParagraphStyle).fastMap { paragraphStyleRange ->
        val annotatedString =
            substringWithoutParagraphStyles(paragraphStyleRange.start, paragraphStyleRange.end)
        block(annotatedString, paragraphStyleRange)
    }
}


fun AnnotatedString.toUpperCase(localeList: LocaleList = LocaleList.current): AnnotatedString {
    return transform { str, start, end -> str.substring(start, end).toUpperCase(localeList) }
}


fun AnnotatedString.toLowerCase(localeList: LocaleList = LocaleList.current): AnnotatedString {
    return transform { str, start, end -> str.substring(start, end).toLowerCase(localeList) }
}


fun AnnotatedString.capitalize(localeList: LocaleList = LocaleList.current): AnnotatedString {
    return transform { str, start, end ->
        if (start == 0) {
            str.substring(start, end).capitalize(localeList)
        } else {
            str.substring(start, end)
        }
    }
}


fun AnnotatedString.decapitalize(localeList: LocaleList = LocaleList.current): AnnotatedString {
    return transform { str, start, end ->
        if (start == 0) {
            str.substring(start, end).decapitalize(localeList)
        } else {
            str.substring(start, end)
        }
    }
}


internal expect fun AnnotatedString.transform(
    transform: (String, Int, Int) -> String
): AnnotatedString


inline fun <R : Any> Builder.withStyle(style: SpanStyle, block: Builder.() -> R): R {
    val index = pushStyle(style)
    return try {
        block(this)
    } finally {
        pop(index)
    }
}


inline fun <R : Any> Builder.withStyle(
    style: ParagraphStyle,
    crossinline block: Builder.() -> R,
): R {
    val index = pushStyle(style)
    return try {
        block(this)
    } finally {
        pop(index)
    }
}


inline fun <R : Any> Builder.withAnnotation(
    tag: String,
    annotation: String,
    crossinline block: Builder.() -> R,
): R {
    val index = pushStringAnnotation(tag, annotation)
    return try {
        block(this)
    } finally {
        pop(index)
    }
}


inline fun <R : Any> Builder.withAnnotation(
    ttsAnnotation: TtsAnnotation,
    crossinline block: Builder.() -> R,
): R {
    val index = pushTtsAnnotation(ttsAnnotation)
    return try {
        block(this)
    } finally {
        pop(index)
    }
}


@ExperimentalTextApi
@Deprecated("Use LinkAnnotation API for links instead", ReplaceWith("withLink(, block)"))
@Suppress("Deprecation")
inline fun <R : Any> Builder.withAnnotation(
    urlAnnotation: UrlAnnotation,
    crossinline block: Builder.() -> R,
): R {
    val index = pushUrlAnnotation(urlAnnotation)
    return try {
        block(this)
    } finally {
        pop(index)
    }
}


inline fun <R : Any> Builder.withLink(link: LinkAnnotation, block: Builder.() -> R): R {
    val index = pushLink(link)
    return try {
        block(this)
    } finally {
        pop(index)
    }
}


private fun <T> filterRanges(ranges: List<Range<out T>>?, start: Int, end: Int): List<Range<T>>? {
    requirePrecondition(start <= end) {
        "start ($start) should be less than or equal to end ($end)"
    }
    val nonNullRange = ranges ?: return null

    return nonNullRange
        .fastFilteredMap({ intersect(start, end, it.start, it.end) }) {
            Range(
                item = it.item,
                start = maxOf(start, it.start) - start,
                end = minOf(end, it.end) - start,
                tag = it.tag,
            )
        }
        .ifEmpty { null }
}


fun AnnotatedString(
    text: String,
    spanStyle: SpanStyle,
    paragraphStyle: ParagraphStyle? = null,
): AnnotatedString =
    AnnotatedString(
        text,
        listOf(Range(spanStyle, 0, text.length)),
        if (paragraphStyle == null) listOf() else listOf(Range(paragraphStyle, 0, text.length)),
    )


fun AnnotatedString(text: String, paragraphStyle: ParagraphStyle): AnnotatedString =
    AnnotatedString(text, listOf(), listOf(Range(paragraphStyle, 0, text.length)))


inline fun buildAnnotatedString(builder: (Builder).() -> Unit): AnnotatedString =
    Builder().apply(builder).toAnnotatedString()


internal fun contains(baseStart: Int, baseEnd: Int, targetStart: Int, targetEnd: Int) =
    (baseStart <= targetStart && targetEnd <= baseEnd) &&
        (baseEnd != targetEnd || (targetStart == targetEnd) == (baseStart == baseEnd))


internal fun intersect(lStart: Int, lEnd: Int, rStart: Int, rEnd: Int): Boolean {
    return ((lStart == lEnd) or (rStart == rEnd) and (lStart == rStart)) or
        ((lStart < rEnd) and (rStart < lEnd))
}

private val EmptyAnnotatedString: AnnotatedString = AnnotatedString("")


internal fun emptyAnnotatedString() = EmptyAnnotatedString
