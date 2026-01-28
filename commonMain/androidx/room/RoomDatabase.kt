
@file:JvmMultifileClass
@file:JvmName("RoomDatabaseKt")

package androidx.room

import androidx.annotation.RestrictTo
import androidx.room.concurrent.CloseBarrier
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.getCoroutineContext
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext


public expect abstract class RoomDatabase() {

    
    public val invalidationTracker: InvalidationTracker

    
    internal val closeBarrier: CloseBarrier

    
    internal fun init(configuration: DatabaseConfiguration)

    
    internal fun createConnectionManager(
        configuration: DatabaseConfiguration
    ): RoomConnectionManager

    
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    protected open fun createOpenDelegate(): RoomOpenDelegateMarker

    
    protected abstract fun createInvalidationTracker(): InvalidationTracker

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP) public fun getCoroutineScope(): CoroutineScope

    
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    public open fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>>

    
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    public open fun createAutoMigrations(
        autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>
    ): List<Migration>

    
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    public fun <T : Any> getTypeConverter(klass: KClass<T>): T

    
    internal fun addTypeConverter(kclass: KClass<*>, converter: Any)

    
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    protected open fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>>

    
    internal val requiredTypeConverterClassesMap: Map<KClass<*>, List<KClass<*>>>

    
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    protected fun internalInitInvalidationTracker(connection: SQLiteConnection)

    
    public fun close()

    
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public suspend fun <R> useConnection(isReadOnly: Boolean, block: suspend (Transactor) -> R): R

    
    public enum class JournalMode {
        
        TRUNCATE,

        
        WRITE_AHEAD_LOGGING,
    }

    
    public class Builder<T : RoomDatabase> {
        
        public fun setDriver(driver: SQLiteDriver): Builder<T>

        
        public fun addMigrations(vararg migrations: Migration): Builder<T>

        
        public fun addAutoMigrationSpec(autoMigrationSpec: AutoMigrationSpec): Builder<T>

        
        public fun fallbackToDestructiveMigration(dropAllTables: Boolean): Builder<T>

        
        public fun fallbackToDestructiveMigrationOnDowngrade(dropAllTables: Boolean): Builder<T>

        
        public fun fallbackToDestructiveMigrationFrom(
            dropAllTables: Boolean,
            vararg startVersions: Int,
        ): Builder<T>

        
        public fun addTypeConverter(typeConverter: Any): Builder<T>

        
        public fun setJournalMode(journalMode: JournalMode): Builder<T>

        
        public fun setQueryCoroutineContext(context: CoroutineContext): Builder<T>

        
        public fun addCallback(callback: Callback): Builder<T>

        
        public fun build(): T
    }

    
    public class MigrationContainer() {
        
        public fun getMigrations(): Map<Int, Map<Int, Migration>>

        
        public fun addMigrations(migrations: List<Migration>)

        
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP) public fun addMigration(migration: Migration)

        
        public fun contains(startVersion: Int, endVersion: Int): Boolean

        
        internal fun getSortedNodes(migrationStart: Int): Pair<Map<Int, Migration>, Iterable<Int>>?

        
        internal fun getSortedDescendingNodes(
            migrationStart: Int
        ): Pair<Map<Int, Migration>, Iterable<Int>>?
    }

    
    public abstract class Callback() {
        
        public open fun onCreate(connection: SQLiteConnection)

        
        public open fun onDestructiveMigration(connection: SQLiteConnection)

        
        public open fun onOpen(connection: SQLiteConnection)
    }
}


public suspend fun <R> RoomDatabase.useReaderConnection(block: suspend (Transactor) -> R): R =
    withContext(getCoroutineContext(false) + RoomExternalOperationElement) {
        useConnection(isReadOnly = true, block)
    }


public suspend fun <R> RoomDatabase.useWriterConnection(block: suspend (Transactor) -> R): R =
    withContext(getCoroutineContext(false) + RoomExternalOperationElement) {
            useConnection(isReadOnly = false, block)
        }
        .also { invalidationTracker.refreshAsync() }


internal object RoomExternalOperationElement :
    CoroutineContext.Element, CoroutineContext.Key<RoomExternalOperationElement> {
    override val key: CoroutineContext.Key<RoomExternalOperationElement>
        get() = RoomExternalOperationElement
}


internal fun validateMigrationsNotRequired(
    migrationStartAndEndVersions: Set<Int>,
    migrationsNotRequiredFrom: Set<Int>,
) {
    if (migrationStartAndEndVersions.isNotEmpty()) {
        for (version in migrationStartAndEndVersions) {
            require(!migrationsNotRequiredFrom.contains(version)) {
                "Inconsistency detected. A Migration was supplied to addMigration() that has a " +
                    "start or end version equal to a start version supplied to " +
                    "fallbackToDestructiveMigrationFrom(). Start version is: $version"
            }
        }
    }
}

internal fun RoomDatabase.validateAutoMigrations(configuration: DatabaseConfiguration) {
    val autoMigrationSpecs = mutableMapOf<KClass<out AutoMigrationSpec>, AutoMigrationSpec>()
    val requiredAutoMigrationSpecs = getRequiredAutoMigrationSpecClasses()
    val usedSpecs = BooleanArray(configuration.autoMigrationSpecs.size)
    for (spec in requiredAutoMigrationSpecs) {
        var foundIndex = -1
        for (providedIndex in configuration.autoMigrationSpecs.indices.reversed()) {
            val provided: Any = configuration.autoMigrationSpecs[providedIndex]
            if (spec.isInstance(provided)) {
                foundIndex = providedIndex
                usedSpecs[foundIndex] = true
                break
            }
        }
        require(foundIndex >= 0) {
            "A required auto migration spec (${spec.qualifiedName}) is missing in the " +
                "database configuration."
        }
        autoMigrationSpecs[spec] = configuration.autoMigrationSpecs[foundIndex]
    }
    for (providedIndex in configuration.autoMigrationSpecs.indices.reversed()) {
        require(providedIndex < usedSpecs.size && usedSpecs[providedIndex]) {
            "Unexpected auto migration specs found. " +
                "Annotate AutoMigrationSpec implementation with " +
                "@ProvidedAutoMigrationSpec annotation or remove this spec from the " +
                "builder."
        }
    }
    val autoMigrations = createAutoMigrations(autoMigrationSpecs)
    for (autoMigration in autoMigrations) {
        val migrationExists =
            configuration.migrationContainer.contains(
                autoMigration.startVersion,
                autoMigration.endVersion,
            )
        if (!migrationExists) {
            configuration.migrationContainer.addMigration(autoMigration)
        }
    }
}

internal fun RoomDatabase.validateTypeConverters(configuration: DatabaseConfiguration) {
    val requiredFactories = this.requiredTypeConverterClassesMap
    val used = BooleanArray(configuration.typeConverters.size)
    requiredFactories.forEach { (daoName, converters) ->
        for (converter in converters) {
            var foundIndex = -1
            for (providedIndex in configuration.typeConverters.indices.reversed()) {
                val provided = configuration.typeConverters[providedIndex]
                if (converter.isInstance(provided)) {
                    foundIndex = providedIndex
                    used[foundIndex] = true
                    break
                }
            }
            require(foundIndex >= 0) {
                "A required type converter (${converter.qualifiedName}) for" +
                    " ${daoName.qualifiedName} is missing in the database configuration."
            }
            addTypeConverter(converter, configuration.typeConverters[foundIndex])
        }
    }
    for (providedIndex in configuration.typeConverters.indices.reversed()) {
        if (!used[providedIndex]) {
            val converter = configuration.typeConverters[providedIndex]
            throw IllegalArgumentException(
                "Unexpected type converter $converter. " +
                    "Annotate TypeConverter class with @ProvidedTypeConverter annotation " +
                    "or remove this converter from the builder."
            )
        }
    }
}
